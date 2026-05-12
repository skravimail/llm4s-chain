package org.l4j.template.llm4s.streaming

import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.runtime.ToolKit

final class StreamingAiRuntime[F[_]](
    backend: StreamingChatBackend[F]
):
  def stream(
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): TokenStream[F] =
    val initialMessages =
      system.map(ChatMessage.SystemMessage.from).toList :+ ChatMessage.UserMessage.from(userText)

    TokenStream(
      backend.stream(
        ChatRequest(
          messages = initialMessages,
          tools = toolKit.schemas,
        )
      )
    )
