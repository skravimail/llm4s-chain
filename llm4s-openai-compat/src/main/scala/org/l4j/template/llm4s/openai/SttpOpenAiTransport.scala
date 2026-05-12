package org.l4j.template.llm4s.openai

import cats.MonadThrow
import cats.syntax.flatMap.*
import sttp.client3.*
import sttp.model.Uri

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
        MonadThrow[F].raiseError(
          RuntimeException(s"OpenAI-compatible request failed with ${response.code.code}: ${response.body}")
        )
    }
