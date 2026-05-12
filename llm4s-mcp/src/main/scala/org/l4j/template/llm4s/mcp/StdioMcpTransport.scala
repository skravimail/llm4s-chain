package org.l4j.template.llm4s.mcp

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*
import upickle.default.*

final class StdioMcpTransport[F[_]: Sync] private (
    nextId: Ref[F, Long],
    readLine: F[String],
    writeLine: String => F[Unit],
) extends McpTransport[F]:

  override def request(method: String, params: Option[ujson.Value]): F[ujson.Value] =
    for
      id <- nextId.modify(current => (current + 1, current))
      payload = McpProtocol.requestPayload(id, method, params)
      _ <- writeLine(write(payload))
      response <- readLine
      decoded <- Sync[F].fromEither(McpProtocol.decodeResponse(response))
      (responseId, result) = decoded
      _ <- Sync[F].raiseWhen(responseId.exists(_ != id))(
        McpProtocolError(
          -32603,
          s"MCP response id ${responseId.getOrElse(-1L)} did not match request id $id for method '$method'",
        )
      )
    yield result

object StdioMcpTransport:
  def create[F[_]: Sync](
      readLine: F[String],
      writeLine: String => F[Unit],
  ): F[StdioMcpTransport[F]] =
    Ref.of[F, Long](1L).map(new StdioMcpTransport(_, readLine, writeLine))

