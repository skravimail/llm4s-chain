package org.l4j.template.llm4s.mcp

import cats.MonadThrow
import cats.syntax.all.*
import upickle.default.*

final class McpClient[F[_]: MonadThrow](
    transport: McpTransport[F]
):

  def listTools(cursor: Option[String] = None): F[McpToolList] =
    val params = cursor.map(value => ujson.Obj("cursor" -> value))
    transport.request("tools/list", params).flatMap(parseToolList)

  def listAllTools: F[List[McpTool]] =
    def loop(cursor: Option[String], acc: List[McpTool]): F[List[McpTool]] =
      listTools(cursor).flatMap { page =>
        page.nextCursor match
          case Some(next) => loop(Some(next), acc ++ page.tools)
          case None       => (acc ++ page.tools).pure[F]
      }

    loop(None, Nil)

  def callTool(name: String, argumentsJson: String): F[McpToolCallResult] =
    Either.catchNonFatal(read[ujson.Value](argumentsJson)) match
      case Left(error) =>
        MonadThrow[F].raiseError(
          McpProtocolError(-32602, s"MCP tool '$name' arguments are not valid JSON: ${error.getMessage}")
        )
      case Right(value) =>
        val arguments = value match
          case obj: ujson.Obj => obj
          case other          => ujson.Obj("value" -> other)
        transport
          .request(
            "tools/call",
            Some(
              ujson.Obj(
                "name" -> name,
                "arguments" -> arguments,
              )
            ),
          )
          .flatMap(parseToolCallResult)

  private def parseToolList(value: ujson.Value): F[McpToolList] =
    MonadThrow[F].fromEither {
      Either.catchNonFatal {
        val obj = value.obj
        val fields = obj.value
        McpToolList(
          tools = obj("tools").arr.toList.map(parseTool),
          nextCursor = fields.get("nextCursor").flatMap(_.strOpt),
        )
      }.leftMap(error => McpProtocolError(-32603, error.getMessage))
    }

  private def parseTool(value: ujson.Value): McpTool =
    val obj = value.obj
    val fields = obj.value
    McpTool(
      name = obj("name").str,
      description = fields.get("description").flatMap(_.strOpt).getOrElse(""),
      inputSchema = fields.get("inputSchema").flatMap(_.objOpt) match
        case Some(schema) => schema
        case None         => ujson.Obj("type" -> "object"),
    )

  private def parseToolCallResult(value: ujson.Value): F[McpToolCallResult] =
    MonadThrow[F].fromEither {
      Either.catchNonFatal {
        val obj = value.obj
        val fields = obj.value
        McpToolCallResult(
          content = fields.get("content").flatMap(_.arrOpt).toList.flatten.map(parseContent).toList,
          structuredContent = fields.get("structuredContent"),
          isError = fields.get("isError").flatMap(_.boolOpt).getOrElse(false),
        )
      }.leftMap(error => McpProtocolError(-32603, error.getMessage))
    }

  private def parseContent(value: ujson.Value): McpContent =
    val obj = value.obj
    val fields = obj.value
    fields.get("type").flatMap(_.strOpt) match
      case Some("text") =>
        McpContent.Text(fields.get("text").flatMap(_.strOpt).getOrElse(""))
      case Some("image") =>
        McpContent.Image(
          data = fields.get("data").flatMap(_.strOpt).getOrElse(""),
          mimeType = fields.get("mimeType").flatMap(_.strOpt).getOrElse("application/octet-stream"),
        )
      case Some("resource") =>
        fields.get("resource").flatMap(_.objOpt) match
          case Some(resource) =>
            val resourceFields = resource.value
            McpContent.File(
              data = resourceFields.get("blob").flatMap(_.strOpt).getOrElse(""),
              mimeType = resourceFields.get("mimeType").flatMap(_.strOpt).getOrElse("application/octet-stream"),
              fileName = resourceFields.get("uri").flatMap(_.strOpt),
            )
          case None =>
            McpContent.Unknown(value)
      case _ =>
        McpContent.Unknown(value)
