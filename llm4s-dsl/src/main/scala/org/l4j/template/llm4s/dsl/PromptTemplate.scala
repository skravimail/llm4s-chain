package org.l4j.template.llm4s.dsl

import cats.Applicative
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ResponseFormat

object PromptTemplate:
  def user[F[_]: Applicative, In](
      system: Option[String] = None,
      responseFormat: Option[ResponseFormat] = None,
      temperature: Option[Double] = None,
      metadata: Map[String, String] = Map.empty,
  )(renderUser: In => String): Runnable[F, In, ChatRequest] =
    Runnable.fromFunction { input =>
      ChatRequest(
        messages =
          system.map(ChatMessage.SystemMessage.from).toList :+
            ChatMessage.UserMessage.from(renderUser(input)),
        responseFormat = responseFormat,
        temperature = temperature,
        metadata = metadata,
      )
    }
