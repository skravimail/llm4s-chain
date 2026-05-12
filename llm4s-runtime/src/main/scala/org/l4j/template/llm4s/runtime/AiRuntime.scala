package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.memory.ChatMemory

final class AiRuntime[F[_]: MonadThrow](
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

  private def loop(
      turn: Int,
      request: ChatRequest,
      toolKit: ToolKit[F],
  ): F[ChatRunResult] =
    if turn >= config.maxTurns then
      MonadThrow[F].raiseError(
        RuntimeException(s"AiRuntime chat exceeded ${config.maxTurns} tool-call turns")
      )
    else
      backend.chat(request).flatMap { response =>
        val aiMessage = response.message
        if !aiMessage.hasToolCalls then
          aiMessage.finishReason match
            case Some(FinishReason.ContentFilter) =>
              MonadThrow[F].raiseError(
                RuntimeException("AiRuntime chat aborted: provider returned finishReason=content_filter")
              )
            case Some(FinishReason.Error) =>
              MonadThrow[F].raiseError(
                RuntimeException("AiRuntime chat aborted: provider returned finishReason=error")
              )
            case _ =>
              MonadThrow[F].pure(
                ChatRunResult(
                  text = response.text,
                  messages = request.messages :+ aiMessage,
                )
              )
        else
          ToolLoop
            .executeAll(
              toolCalls = aiMessage.toolCalls,
              toolKit = toolKit,
              turn = turn + 1,
              request = request,
            )
            .flatMap { toolMessages =>
              val nextRequest = request.copy(
                messages = request.messages ++ (aiMessage :: toolMessages),
                tools = toolKit.schemas,
              )
              loop(turn + 1, nextRequest, toolKit)
            }
      }

  private def dropLeadingSystem(messages: List[ChatMessage]): List[ChatMessage] =
    messages match
      case (_: ChatMessage.SystemMessage) :: tail => tail
      case other                                  => other
