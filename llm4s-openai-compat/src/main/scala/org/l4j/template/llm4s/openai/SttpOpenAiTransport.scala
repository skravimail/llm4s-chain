package org.l4j.template.llm4s.openai

import cats.MonadThrow
import cats.syntax.flatMap.*
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
) extends OpenAiTransport[F]:

  override def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): F[ujson.Value] =
    val uri = path.split('/').filter(_.nonEmpty).foldLeft(baseUri)(_ addPath _)
    val request = basicRequest
      .post(uri)
      .headers(headers)
      .contentType("application/json")
      .response(asStringAlways)
      .body(ujson.write(body))

    backend.send(request).flatMap { response =>
      if response.code.isSuccess then
        MonadThrow[F].catchNonFatal(ujson.read(response.body))
      else
        MonadThrow[F].raiseError(OpenAiHttpError.fromResponse(response.code.code, response.body))
    }
