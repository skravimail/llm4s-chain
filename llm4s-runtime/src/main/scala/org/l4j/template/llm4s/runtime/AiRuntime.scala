package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest

final class AiRuntime[F[_]: MonadThrow](
    backend: ChatBackend[F],
    config: RuntimeConfig = RuntimeConfig(),
):

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
    loop(turn = 0, request = request.copy(tools = toolKit.schemas), toolKit = toolKit)

  private def loop(
      turn: Int,
      request: ChatRequest,
      toolKit: ToolKit[F],
  ): F[String] =
    if turn >= config.maxTurns then
      MonadThrow[F].raiseError(
        RuntimeException(s"AiRuntime chat exceeded ${config.maxTurns} tool-call turns")
      )
    else
      backend.chat(request).flatMap { response =>
        val aiMessage = response.message
        if !aiMessage.hasToolCalls then
          MonadThrow[F].pure(response.text)
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
