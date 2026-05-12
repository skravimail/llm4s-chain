package org.l4j.template.llm4s.streaming

import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall

sealed trait StreamEvent

object StreamEvent:
  final case class TextDelta(value: String) extends StreamEvent
  final case class ThinkingDelta(value: String) extends StreamEvent
  final case class ToolCallDelta(
      callId: Option[String],
      name: Option[String],
      argumentsFragment: String,
  ) extends StreamEvent
  final case class ToolCallCompleted(call: ToolCall) extends StreamEvent
  final case class Completed(response: ChatResponse) extends StreamEvent
