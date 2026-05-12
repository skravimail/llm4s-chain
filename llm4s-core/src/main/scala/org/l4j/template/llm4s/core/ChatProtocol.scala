package org.l4j.template.llm4s.core

sealed trait ChatMessage:
  def role: String
  def contents: List[AiContent]

  def text: String =
    contents.flatMap(_.textValue).mkString

object ChatMessage:
  final case class SystemMessage(
      contents: List[AiContent]
  ) extends ChatMessage:
    override val role: String = "system"

  object SystemMessage:
    def from(text: String): SystemMessage =
      SystemMessage(List(AiContent.Text(text)))

  final case class UserMessage(
      contents: List[AiContent]
  ) extends ChatMessage:
    override val role: String = "user"

  object UserMessage:
    def from(text: String): UserMessage =
      UserMessage(List(AiContent.Text(text)))

  final case class AiMessage(
      contents: List[AiContent],
      toolCalls: List[ToolCall] = Nil,
      finishReason: Option[FinishReason] = None,
  ) extends ChatMessage:
    override val role: String = "assistant"

    def hasToolCalls: Boolean = toolCalls.nonEmpty

  object AiMessage:
    def from(text: String): AiMessage =
      AiMessage(List(AiContent.Text(text)))

  final case class ToolResultMessage(
      toolName: String,
      toolCallId: Option[String],
      result: ToolResult,
  ) extends ChatMessage:
    override val role: String = "tool"
    override def contents: List[AiContent] = result match
      case ToolResult.Text(value, _)           => List(AiContent.Text(value))
      case ToolResult.StructuredJson(value, _) => List(AiContent.Text(value))
      case ToolResult.Content(parts, _)        => parts

final case class ChatRequest(
    messages: List[ChatMessage],
    tools: List[ToolSchema] = Nil,
    responseFormat: Option[ResponseFormat] = None,
    temperature: Option[Double] = None,
    metadata: Map[String, String] = Map.empty,
):
  def withTool(tool: ToolSchema): ChatRequest =
    copy(tools = tools :+ tool)

final case class ChatResponse(
    message: ChatMessage.AiMessage,
    usage: Option[Usage] = None,
    finishReason: Option[FinishReason] = None,
    responseId: Option[String] = None,
    metadata: Map[String, String] = Map.empty,
):
  def text: String = message.text
