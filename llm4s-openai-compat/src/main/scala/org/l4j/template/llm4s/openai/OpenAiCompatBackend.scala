package org.l4j.template.llm4s.openai

import cats.MonadThrow
import cats.syntax.functor.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse

final class OpenAiCompatBackend[F[_]: MonadThrow](
    config: OpenAiCompatConfig,
    transport: OpenAiTransport[F],
) extends ChatBackend[F]:

  override def chat(request: ChatRequest): F[ChatResponse] =
    val headers = Map(
      "Authorization" -> s"Bearer ${config.apiKey}"
    ) ++ config.defaultHeaders

    transport
      .post(
        path = "/chat/completions",
        body = OpenAiWire.encodeChatRequest(config.model, request),
        headers = headers,
      )
      .map(OpenAiWire.decodeChatResponse)
