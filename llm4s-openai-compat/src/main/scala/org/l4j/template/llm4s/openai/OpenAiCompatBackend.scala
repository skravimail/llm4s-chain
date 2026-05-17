package org.l4j.template.llm4s.openai

import cats.MonadThrow
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.functor.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.TraceContext
import sttp.client3.SttpBackend
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Uri

final class OpenAiCompatBackend[F[_]: MonadThrow](
    config: OpenAiCompatConfig,
    transport: OpenAiTransport[F],
) extends ChatBackend[F]:

  override def chat(request: ChatRequest): F[ChatResponse] =
    chat(request, TraceContext.fresh())

  /** Trace-aware override (PR-8f) — forwards the caller's `TraceContext`
    * to the transport so HTTP-layer events fire under the same trace as
    * the chat. */
  override def chat(request: ChatRequest, trace: TraceContext): F[ChatResponse] =
    val headers = Map(
      "Authorization" -> s"Bearer ${config.apiKey}"
    ) ++ config.defaultHeaders

    transport
      .post(
        path = "/chat/completions",
        body = OpenAiWire.encodeChatRequest(config.model, request),
        headers = headers,
        trace = trace,
      )
      .map(OpenAiWire.decodeChatResponse)

object OpenAiCompatBackend:

  /** Build a backend whose lifetime is owned by `Resource`. The underlying
    * sttp `AsyncHttpClient` (and its thread pool) is closed when the resource
    * is released, so callers cannot accidentally leak HTTP client state on
    * shutdown.
    *
    * Prefer this over instantiating `OpenAiCompatBackend` directly when you
    * also build the sttp backend yourself — the manual form is fine for
    * tests or for sharing a backend across multiple wrappers, but production
    * code should hand lifetime to `Resource`.
    */
  def resource[F[_]: Async](config: OpenAiCompatConfig): Resource[F, ChatBackend[F]] =
    AsyncHttpClientCatsBackend.resource[F]().map { sttp =>
      fromSttp(config, sttp)
    }

  /** Wrap an externally-owned sttp backend. The caller keeps responsibility
    * for closing it. */
  def fromSttp[F[_]: MonadThrow](
      config: OpenAiCompatConfig,
      sttpBackend: SttpBackend[F, Any],
  ): OpenAiCompatBackend[F] =
    val transport = SttpOpenAiTransport[F](
      baseUri = Uri.unsafeParse(config.baseUrl),
      backend = sttpBackend,
    )
    new OpenAiCompatBackend[F](config, transport)
