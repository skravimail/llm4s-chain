package org.l4j.template.llm4s.mcp

import org.l4j.template.llm4s.core.JsonSchema

object McpSchemaConverter:
  def toObjectSchema(value: ujson.Obj): JsonSchema.ObjectSchema =
    toSchema(value) match
      case objectSchema: JsonSchema.ObjectSchema => objectSchema
      case _                                     => JsonSchema.ObjectSchema(Map.empty)

  private def toSchema(value: ujson.Value): JsonSchema =
    val obj: ujson.Obj = value.objOpt match
      case Some(found) => found
      case None        => ujson.Obj()
    val fields = obj.value
    val description = fields.get("description").flatMap(_.strOpt)

    fields.get("enum").flatMap(_.arrOpt) match
      case Some(values) =>
        JsonSchema.EnumSchema(values.toList.flatMap(_.strOpt), description)
      case None =>
        fields.get("type").flatMap(_.strOpt) match
          case Some("object") =>
            val properties = fields
              .get("properties")
              .flatMap(_.objOpt)
              .map(_.value.toMap.view.mapValues(toSchema).toMap)
              .getOrElse(Map.empty)
            JsonSchema.ObjectSchema(
              properties = properties,
              required = fields.get("required").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt).toSet).getOrElse(Set.empty),
              description = description,
            )
          case Some("array") =>
            JsonSchema.ArraySchema(fields.get("items").map(toSchema).getOrElse(JsonSchema.StringSchema()), description)
          case Some("integer") => JsonSchema.IntegerSchema(description)
          case Some("number")  => JsonSchema.NumberSchema(description)
          case Some("boolean") => JsonSchema.BooleanSchema(description)
          case Some("string")  => JsonSchema.StringSchema(description)
          case _ if fields.contains("anyOf") =>
            JsonSchema.AnyOfSchema(fields("anyOf").arr.toList.map(toSchema), description)
          case _ if fields.contains("$ref") =>
            JsonSchema.RefSchema(fields("$ref").str, description)
          case _ =>
            JsonSchema.StringSchema(description)
