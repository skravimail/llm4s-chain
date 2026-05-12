package org.l4j.template.llm4s.core

sealed trait AiContent:
  def textValue: Option[String]
  def requiredCapabilities: Set[ModelCapability]

object AiContent:
  final case class Text(value: String) extends AiContent:
    override val textValue: Option[String] = Some(value)
    override val requiredCapabilities: Set[ModelCapability] = Set.empty

  final case class Image(
      base64Data: String,
      mimeType: String,
      detail: Option[String] = None,
  ) extends AiContent:
    override val textValue: Option[String] = None
    override val requiredCapabilities: Set[ModelCapability] = Set(ModelCapability.VisionInput)

  final case class File(
      base64Data: String,
      mimeType: String,
      fileName: Option[String] = None,
  ) extends AiContent:
    override val textValue: Option[String] = None
    override val requiredCapabilities: Set[ModelCapability] = Set(ModelCapability.FileInput)
