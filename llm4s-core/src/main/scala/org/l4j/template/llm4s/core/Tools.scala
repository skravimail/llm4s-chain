package org.l4j.template.llm4s.core

final case class ToolSchema(
    name: String,
    description: String,
    parameters: JsonSchema.ObjectSchema,
)

final case class ToolCall(
    name: String,
    argumentsJson: String,
    callId: Option[String] = None,
)

sealed trait ToolResult:
  def isError: Boolean
  def text: String

object ToolResult:
  final case class Text(
      value: String,
      isError: Boolean = false,
  ) extends ToolResult:
    override def text: String = value

  final case class StructuredJson(
      value: String,
      isError: Boolean = false,
  ) extends ToolResult:
    override def text: String = value

  final case class Content(
      parts: List[AiContent],
      isError: Boolean = false,
  ) extends ToolResult:
    override def text: String =
      parts.flatMap(_.textValue).mkString
