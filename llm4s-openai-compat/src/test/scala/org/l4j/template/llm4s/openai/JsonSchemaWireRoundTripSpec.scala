package org.l4j.template.llm4s.openai

import munit.FunSuite
import org.l4j.template.llm4s.core.JsonSchema

/** JsonSchema → wire JSON (via the OpenAI encoder) → check that every public
  * field appears in the emitted JSON. We don't ship a JSON→JsonSchema
  * decoder (decoding arbitrary schemas is a separate beast), so the
  * "round-trip" here is structural: every field documented in the ADT must
  * survive encoding.
  */
class JsonSchemaWireRoundTripSpec extends FunSuite:

  // The encoder lives inside OpenAiWire as `private`. Use the public path
  // (encodeChatRequest) by stuffing the schema into a tool's parameters.
  private def encode(schema: JsonSchema): ujson.Value =
    val request = org.l4j.template.llm4s.core.ChatRequest(
      messages = Nil,
      tools = List(org.l4j.template.llm4s.core.ToolSchema("t", "d", schema match {
        case obj: JsonSchema.ObjectSchema => obj
        case other =>
          JsonSchema.ObjectSchema(
            properties = Map("value" -> other),
            required = Set("value"),
          )
      })),
    )
    OpenAiWire.encodeChatRequest("m", request)("tools").arr.head("function")("parameters")

  test("StringSchema encodes type/description/enum") {
    val json = encode(JsonSchema.StringSchema(description = Some("d"), enumValues = List("a", "b")))
    val inner = json("properties")("value")
    assertEquals(inner("type").str, "string")
    assertEquals(inner("description").str, "d")
    assertEquals(inner("enum").arr.toList.map(_.str), List("a", "b"))
  }

  test("IntegerSchema and NumberSchema differentiate type") {
    assertEquals(
      encode(JsonSchema.IntegerSchema())("properties")("value")("type").str,
      "integer",
    )
    assertEquals(
      encode(JsonSchema.NumberSchema())("properties")("value")("type").str,
      "number",
    )
  }

  test("ArraySchema encodes items recursively") {
    val json = encode(JsonSchema.ArraySchema(JsonSchema.StringSchema()))
    val arr = json("properties")("value")
    assertEquals(arr("type").str, "array")
    assertEquals(arr("items")("type").str, "string")
  }

  test("ObjectSchema preserves required-set membership") {
    val schema = JsonSchema.ObjectSchema(
      properties = Map(
        "a" -> JsonSchema.StringSchema(),
        "b" -> JsonSchema.IntegerSchema(),
      ),
      required = Set("a"),
    )
    val json = encode(schema)
    assertEquals(json("required").arr.toList.map(_.str), List("a"))
    assertEquals(json("properties")("a")("type").str, "string")
    assertEquals(json("properties")("b")("type").str, "integer")
  }

  test("EnumSchema maps to type=string + enum") {
    val schema = JsonSchema.ObjectSchema(
      Map("level" -> JsonSchema.EnumSchema(List("Low", "High"))),
      Set("level"),
    )
    val json = encode(schema)
    val lvl = json("properties")("level")
    assertEquals(lvl("type").str, "string")
    assertEquals(lvl("enum").arr.toList.map(_.str), List("Low", "High"))
  }

  test("AnyOfSchema encodes anyOf alternatives") {
    val schema = JsonSchema.ObjectSchema(
      Map("either" -> JsonSchema.AnyOfSchema(List(JsonSchema.StringSchema(), JsonSchema.IntegerSchema()))),
      Set("either"),
    )
    val any = encode(schema)("properties")("either")
    assertEquals(any("anyOf").arr.toList.map(_("type").str), List("string", "integer"))
  }

  test("RefSchema emits $ref with the definitions/ prefix") {
    val schema = JsonSchema.ObjectSchema(
      properties = Map("p" -> JsonSchema.RefSchema("Pointer")),
      required = Set("p"),
      definitions = Map("Pointer" -> JsonSchema.StringSchema()),
    )
    val json = encode(schema)
    assertEquals(json("properties")("p")("$ref").str, "#/definitions/Pointer")
    assertEquals(json("definitions")("Pointer")("type").str, "string")
  }
