package org.l4j.template.llm4s.core

sealed trait JsonSchema:
  def description: Option[String]

object JsonSchema:
  final case class ObjectSchema(
      properties: Map[String, JsonSchema],
      required: Set[String] = Set.empty,
      description: Option[String] = None,
      definitions: Map[String, JsonSchema] = Map.empty,
  ) extends JsonSchema:
    def propertyNames: Set[String] = properties.keySet

  final case class StringSchema(
      description: Option[String] = None,
      enumValues: List[String] = Nil,
  ) extends JsonSchema

  final case class IntegerSchema(
      description: Option[String] = None
  ) extends JsonSchema

  final case class NumberSchema(
      description: Option[String] = None
  ) extends JsonSchema

  final case class BooleanSchema(
      description: Option[String] = None
  ) extends JsonSchema

  final case class ArraySchema(
      items: JsonSchema,
      description: Option[String] = None,
  ) extends JsonSchema

  final case class EnumSchema(
      values: List[String],
      description: Option[String] = None,
  ) extends JsonSchema

  final case class RefSchema(
      reference: String,
      description: Option[String] = None,
  ) extends JsonSchema

  final case class AnyOfSchema(
      alternatives: List[JsonSchema],
      description: Option[String] = None,
  ) extends JsonSchema
