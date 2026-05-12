package org.l4j.template.llm4s.openai

import fs2.Stream
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.streaming.StreamEvent
import org.l4j.template.llm4s.streaming.StreamingChatBackend

final class OpenAiCompatStreamingBackend[F[_]](
    config: OpenAiCompatConfig,
    transport: OpenAiStreamingTransport[F],
) extends StreamingChatBackend[F]:

  override def stream(request: ChatRequest): Stream[F, StreamEvent] =
    val headers = Map(
      "Authorization" -> s"Bearer ${config.apiKey}"
    ) ++ config.defaultHeaders

    val body = OpenAiWire.encodeChatRequest(config.model, request)
    body("stream") = ujson.Bool(true)

    transport
      .stream("/chat/completions", body, headers)
      .flatMap(line => Stream.emits(OpenAiStreamDecoder.decodeLine(line)))
