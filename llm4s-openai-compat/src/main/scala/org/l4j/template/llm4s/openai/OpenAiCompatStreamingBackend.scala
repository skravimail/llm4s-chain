package org.l4j.template.llm4s.openai

import cats.effect.kernel.Resource
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

object OpenAiCompatStreamingBackend:

  /** Build a streaming backend whose underlying transport is owned by the
    * returned `Resource`. Provider-specific SSE transports must be supplied
    * as a `Resource` so any threads / connections they hold are released on
    * shutdown — see PR-5 in CODE_REVIEW.md.
    */
  def resource[F[_]](
      config: OpenAiCompatConfig,
      transport: Resource[F, OpenAiStreamingTransport[F]],
  ): Resource[F, StreamingChatBackend[F]] =
    transport.map(t => new OpenAiCompatStreamingBackend[F](config, t))
