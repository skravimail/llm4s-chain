package org.l4j.template.llm4s.core

sealed trait AiContent:
  def textValue: Option[String]

object AiContent:
  final case class Text(value: String) extends AiContent:
    override val textValue: Option[String] = Some(value)

  final case class Image(
      base64Data: String,
      mimeType: String,
      detail: Option[String] = None,
  ) extends AiContent:
    override val textValue: Option[String] = None
