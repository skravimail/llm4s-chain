package org.l4j.template.llm4s.core

enum ModelCapability:
  case ToolCalling
  case StructuredOutputJsonSchema
  case Streaming
  case VisionInput
  case FileInput
  case MultimodalToolResult
  case Thinking
  case Embeddings
  case Moderation

final case class ModelCapabilities(
    supported: Set[ModelCapability]
):
  def supports(capability: ModelCapability): Boolean =
    supported.contains(capability)

  def missing(required: Set[ModelCapability]): Set[ModelCapability] =
    required.diff(supported)

  def validate(request: ChatRequest): Either[UnsupportedModelCapabilities, Unit] =
    val missingCapabilities = missing(request.requiredCapabilities)
    Either.cond(
      missingCapabilities.isEmpty,
      (),
      UnsupportedModelCapabilities(missingCapabilities),
    )

final case class UnsupportedModelCapabilities(
    missing: Set[ModelCapability]
) extends RuntimeException(
      s"Model does not support required capabilities: ${missing.toList.map(_.toString).sorted.mkString(",")}"
    )

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
