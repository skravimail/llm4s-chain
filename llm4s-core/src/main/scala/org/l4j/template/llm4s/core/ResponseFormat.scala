package org.l4j.template.llm4s.core

sealed trait ResponseFormat

object ResponseFormat:
  case object Text extends ResponseFormat

  final case class JsonSchema(
      name: String,
      schema: org.l4j.template.llm4s.core.JsonSchema,
      strict: Boolean = false,
  ) extends ResponseFormat
