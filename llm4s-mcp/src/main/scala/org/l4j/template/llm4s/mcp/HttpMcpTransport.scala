package org.l4j.template.llm4s.mcp

import cats.MonadThrow
import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import sttp.client3.SttpBackend
import sttp.client3.basicRequest
import sttp.model.Uri
import upickle.default.*

final class HttpMcpTransport[F[_]: MonadThrow] private (
    nextId: Ref[F, Long],
    endpoint: Uri,
    backend: SttpBackend[F, Any],
    headers: Map[String, String],
) extends McpTransport[F]:

  override def request(method: String, params: Option[ujson.Value]): F[ujson.Value] =
    for
      id <- nextId.modify(current => (current + 1, current))
      payload = write(McpProtocol.requestPayload(id, method, params))
      response <- basicRequest
        .post(endpoint)
        .headers(headers)
        .contentType("application/json")
        .body(payload)
        .send(backend)
      body <- MonadThrow[F].fromEither(response.body.left.map(RuntimeException(_)))
      result <- MonadThrow[F].fromEither(McpProtocol.decodeResult(body))
    yield result

object HttpMcpTransport:
  def create[F[_]: Sync](
      endpoint: Uri,
      backend: SttpBackend[F, Any],
      headers: Map[String, String] = Map.empty,
  ): F[HttpMcpTransport[F]] =
    Ref.of[F, Long](1L).map(new HttpMcpTransport(_, endpoint, backend, headers))
