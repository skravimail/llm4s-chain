package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason

object AiRuntime:
  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
  ): AiRuntime[F] =
    new AiRuntime[F](backend, RuntimeConfig(), RuntimeListener.noop[F])

  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      config: RuntimeConfig,
  ): AiRuntime[F] =
    new AiRuntime[F](backend, config, RuntimeListener.noop[F])

  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      config: RuntimeConfig,
      listener: RuntimeListener[F],
  ): AiRuntime[F] =
    new AiRuntime[F](backend, config, listener)

final class AiRuntime[F[_]: MonadThrow: Parallel](
    backend: ChatBackend[F],
    config: RuntimeConfig,
    listener: RuntimeListener[F],
):

  private final case class ChatRunResult(
      text: String,
      messages: List[ChatMessage],
  )

  def chat(
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    val initialMessages =
      system.map(ChatMessage.SystemMessage.from).toList :+ ChatMessage.UserMessage.from(userText)
    chatRequest(
      ChatRequest(
        messages = initialMessages,
        tools = toolKit.schemas,
      ),
      toolKit,
    )

  def chatRequest(
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    run(request, toolKit).map(_.text)

  /** Drive the loop and return both the final assistant text and the full
    * messages list (initial + assistant turns + tool messages).
    *
    * Exposed so that wrappers in other modules (e.g. `llm4s-memory`'s
    * `MemoryAwareRuntime`) can persist the transcript without re-running
    * the conversation — see PR-14 in CODE_REVIEW.md. */
  def chatRequestWithMessages(
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[(String, List[ChatMessage])] =
    run(request, toolKit).map(r => (r.text, r.messages))

  private def run(
      request: ChatRequest,
      toolKit: ToolKit[F],
  ): F[ChatRunResult] =
    val initial = request.copy(tools = toolKit.schemas)
    for
      _ <- listener.onChatStarted(initial)
      result <- loop(turn = 0, request = initial, toolKit = toolKit)
      _ <- listener.onChatCompleted(initial, result.text, result.messages.length - initial.messages.length)
    yield result

  /** Drive the chat loop in a stack-safe way via `tailRecM`.
    *
    * Each iteration's state is the current turn count and request. A `Right`
    * exits the loop with a final result; a `Left` schedules another iteration.
    * Any monad that implements `tailRecM` non-recursively (cats-effect IO,
    * Eval, etc.) will not stack-overflow regardless of `maxTurns`.
    */
  private def loop(
      turn: Int,
      request: ChatRequest,
      toolKit: ToolKit[F],
  ): F[ChatRunResult] =
    MonadThrow[F].tailRecM[(Int, ChatRequest), ChatRunResult]((turn, request)) {
      case (currentTurn, currentRequest) =>
        if currentTurn >= config.maxTurns then
          MonadThrow[F].raiseError(AiRuntimeError.MaxTurnsExceeded(config.maxTurns))
        else
          backend.chat(currentRequest).flatMap { response =>
            val aiMessage = response.message
            if !aiMessage.hasToolCalls then
              aiMessage.finishReason match
                case Some(FinishReason.ContentFilter) =>
                  MonadThrow[F].raiseError(AiRuntimeError.ContentFiltered)
                case Some(FinishReason.Error) =>
                  MonadThrow[F].raiseError(AiRuntimeError.ProviderError())
                case _ =>
                  MonadThrow[F].pure(
                    Right(
                      ChatRunResult(
                        text = response.text,
                        messages = currentRequest.messages :+ aiMessage,
                      )
                    )
                  )
            else
              ToolLoop
                .executeAll(
                  toolCalls = aiMessage.toolCalls,
                  toolKit = toolKit,
                  turn = currentTurn + 1,
                  request = currentRequest,
                  config = config,
                  listener = listener,
                )
                .map { toolMessages =>
                  val nextRequest = currentRequest.copy(
                    messages = currentRequest.messages ++ (aiMessage :: toolMessages),
                    tools = toolKit.schemas,
                  )
                  Left((currentTurn + 1, nextRequest))
                }
          }
    }

