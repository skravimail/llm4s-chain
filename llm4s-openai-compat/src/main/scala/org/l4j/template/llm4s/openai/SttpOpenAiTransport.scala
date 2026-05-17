package org.l4j.template.llm4s.openai

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.TraceContext
import sttp.client3.*
import sttp.model.Uri

sealed abstract class OpenAiHttpError(val statusCode: Int, message: String)
    extends RuntimeException(message):
  def body: String

object OpenAiHttpError:
  final case class Unauthorized(override val body: String)
      extends OpenAiHttpError(401, s"OpenAI-compatible request unauthorized (401): $body")
  final case class Forbidden(override val body: String)
      extends OpenAiHttpError(403, s"OpenAI-compatible request forbidden (403): $body")
  final case class RateLimited(override val body: String)
      extends OpenAiHttpError(429, s"OpenAI-compatible request rate limited (429): $body")
  final case class Client(override val statusCode: Int, override val body: String)
      extends OpenAiHttpError(statusCode, s"OpenAI-compatible client error ($statusCode): $body")
  final case class Server(override val statusCode: Int, override val body: String)
      extends OpenAiHttpError(statusCode, s"OpenAI-compatible server error ($statusCode): $body")

  def fromResponse(status: Int, body: String): OpenAiHttpError =
    status match
      case 401              => Unauthorized(body)
      case 403              => Forbidden(body)
      case 429              => RateLimited(body)
      case s if s >= 500    => Server(s, body)
      case s                => Client(s, body)

final class SttpOpenAiTransport[F[_]: MonadThrow](
    baseUri: Uri,
    backend: SttpBackend[F, Any],
    listener: HttpListener[F],
) extends OpenAiTransport[F]:

  override def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): F[ujson.Value] =
    post(path, body, headers, TraceContext.fresh())

  /** Trace-aware send path (PR-8f). Fires `HttpListener` events keyed by
    * the caller's `TraceContext` so HTTP events correlate with chat / tool
    * events without needing an `IOLocal`-based tracer. */
  override def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
      trace: TraceContext,
  ): F[ujson.Value] =
    val uri = path.split('/').filter(_.nonEmpty).foldLeft(baseUri)(_ addPath _)
    val request = basicRequest
      .post(uri)
      .headers(headers)
      .contentType("application/json")
      .response(asStringAlways)
      .body(ujson.write(body))

    listener.onHttpRequest(trace, request.method, request.uri) >> {
      val started = MonadThrow[F].pure(System.nanoTime())
      started.flatMap { start =>
        backend
          .send(request)
          .attempt
          .flatMap {
            case Right(response) =>
              val duration = System.nanoTime() - start
              listener
                .onHttpResponse(trace, request.method, request.uri, response.code.code, duration)
                .productR(
                  if response.code.isSuccess then
                    MonadThrow[F].catchNonFatal(ujson.read(response.body))
                  else
                    MonadThrow[F].raiseError(
                      OpenAiHttpError.fromResponse(response.code.code, response.body)
                    )
                )
            case Left(err) =>
              val duration = System.nanoTime() - start
              listener
                .onHttpFailure(trace, request.method, request.uri, err, duration)
                .productR(MonadThrow[F].raiseError(err))
          }
      }
    }

object SttpOpenAiTransport:
  def apply[F[_]: MonadThrow](
      baseUri: Uri,
      backend: SttpBackend[F, Any],
  ): SttpOpenAiTransport[F] =
    new SttpOpenAiTransport[F](baseUri, backend, HttpListener.noop[F])

  def apply[F[_]: MonadThrow](
      baseUri: Uri,
      backend: SttpBackend[F, Any],
      listener: HttpListener[F],
  ): SttpOpenAiTransport[F] =
    new SttpOpenAiTransport[F](baseUri, backend, listener)
