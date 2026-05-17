package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.memory.ChatMemory

final class AiRuntime[F[_]: MonadThrow: Parallel](
    backend: ChatBackend[F],
    config: RuntimeConfig = RuntimeConfig(),
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

  def chatWithMemory[Id](
      memory: ChatMemory[F, Id],
      memoryId: Id,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    memory.messages(memoryId).flatMap { history =>
      val initialMessages =
        system.map(ChatMessage.SystemMessage.from).toList ++ history ++ List(ChatMessage.UserMessage.from(userText))

      run(
        ChatRequest(
          messages = initialMessages,
          tools = toolKit.schemas,
        ),
        toolKit,
      ).flatMap { result =>
        val persisted = dropLeadingSystem(result.messages)
        memory.replace(memoryId, persisted).as(result.text)
      }
    }

  def chatRequest(
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    run(request, toolKit).map(_.text)

  def chatRequestWithMemory[Id](
      memory: ChatMemory[F, Id],
      memoryId: Id,
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    run(request, toolKit).flatMap { result =>
      memory.replace(memoryId, dropLeadingSystem(result.messages)).as(result.text)
    }

  private def run(
      request: ChatRequest,
      toolKit: ToolKit[F],
  ): F[ChatRunResult] =
    loop(turn = 0, request = request.copy(tools = toolKit.schemas), toolKit = toolKit)

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

  private def dropLeadingSystem(messages: List[ChatMessage]): List[ChatMessage] =
    messages match
      case (_: ChatMessage.SystemMessage) :: tail => tail
      case other                                  => other
