package org.l4j.template.llm4s.mcp

import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ToolResult
import upickle.default.*

final case class McpProtocolError(code: Int, message: String) extends RuntimeException(message)

final case class McpTool(
    name: String,
    description: String,
    inputSchema: ujson.Obj,
)

final case class McpToolList(
    tools: List[McpTool],
    nextCursor: Option[String] = None,
)

sealed trait McpContent:
  def toAiContent: Option[AiContent]

object McpContent:
  final case class Text(text: String) extends McpContent:
    override val toAiContent: Option[AiContent] = Some(AiContent.Text(text))

  final case class Image(data: String, mimeType: String) extends McpContent:
    override val toAiContent: Option[AiContent] = Some(AiContent.Image(data, mimeType))

  final case class Unknown(value: ujson.Value) extends McpContent:
    override val toAiContent: Option[AiContent] = Some(AiContent.Text(write(value)))

final case class McpToolCallResult(
    content: List[McpContent],
    structuredContent: Option[ujson.Value],
    isError: Boolean = false,
):
  def toToolResult: ToolResult =
    structuredContent match
      case Some(value) => ToolResult.StructuredJson(write(value), isError)
      case None =>
        ToolResult.Content(content.flatMap(_.toAiContent), isError)

object McpProtocol:
  def requestPayload(id: Long, method: String, params: Option[ujson.Value]): ujson.Obj =
    val obj = ujson.Obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> method,
    )
    params.foreach(value => obj("params") = value)
    obj

  def decodeResult(payload: String): Either[McpProtocolError, ujson.Value] =
    decodeResult(read[ujson.Value](payload))

  def decodeResult(payload: ujson.Value): Either[McpProtocolError, ujson.Value] =
    payload.objOpt match
      case None =>
        Left(McpProtocolError(-32603, "MCP response was not a JSON object"))
      case Some(obj) if obj.value.contains("error") =>
        val error = obj.value("error")
        val errorFields = error.obj.value
        Left(
          McpProtocolError(
            code = errorFields.get("code").flatMap(_.numOpt).map(_.toInt).getOrElse(-32603),
            message = errorFields.get("message").flatMap(_.strOpt).getOrElse("MCP protocol error"),
          )
        )
      case Some(obj) =>
        obj.value.get("result").toRight(McpProtocolError(-32603, "MCP response did not include result"))
