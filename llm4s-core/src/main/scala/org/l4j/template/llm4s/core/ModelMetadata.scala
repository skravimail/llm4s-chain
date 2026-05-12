package org.l4j.template.llm4s.core

enum ModelCapability:
  case ToolCalling
  case StructuredOutputJsonSchema
  case Streaming
  case VisionInput
  case MultimodalToolResult
  case Thinking
  case Embeddings
  case Moderation

final case class ModelCapabilities(
    supported: Set[ModelCapability]
):
  def supports(capability: ModelCapability): Boolean =
    supported.contains(capability)

final case class Usage(
    inputTokens: Int,
    outputTokens: Int,
):
  def totalTokens: Int = inputTokens + outputTokens

enum FinishReason:
  case Stop
  case Length
  case ToolCalls
  case ContentFilter
  case Error
