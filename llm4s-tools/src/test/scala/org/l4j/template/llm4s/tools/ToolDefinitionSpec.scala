package org.l4j.template.llm4s.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.InvocationContext

class ToolDefinitionSpec extends FunSuite:

  test("schema derivation supports nested products optional fields lists and enums") {
    val schema = summon[SchemaEncoder[SearchArgs]].schema

    val root = schema.asInstanceOf[JsonSchema.ObjectSchema]
    val filters = root.properties("filters").asInstanceOf[JsonSchema.ObjectSchema]
    val level = root.properties("level").asInstanceOf[JsonSchema.EnumSchema]
    val tags = root.properties("tags").asInstanceOf[JsonSchema.ArraySchema]

    assertEquals(root.required, Set("query", "level", "tags"))
    assertEquals(filters.required, Set("region", "active"))
    assertEquals(level.values, List("Low", "High"))
    assert(tags.items.isInstanceOf[JsonSchema.StringSchema])
  }

  test("value decoding supports nested products optional fields lists and enums") {
    val decoded = summon[ValueDecoder[SearchArgs]].decode(
      ujson.read(
        """{
          |  "query": "scala",
          |  "filters": { "region": "us", "active": true },
          |  "tags": ["backend", "jvm"],
          |  "level": "High"
          |}""".stripMargin
      )
    )

    assertEquals(
      decoded,
      Right(SearchArgs("scala", Some(Filters("us", true)), List("backend", "jvm"), ReviewLevel.High)),
    )
  }

  test("tool definition decodes structured arguments before execution") {
    val definition = ToolDefinition.fromProduct[IO, SearchArgs](
      name = "search",
      description = "Search indexed docs",
    ) { args =>
      IO.pure(ToolResult.Text(s"${args.query}:${args.level}:${args.tags.mkString("|")}"))
    }

    val result = definition.executor.execute(
      ToolCall(
        "search",
        """{"query":"scala","tags":["backend"],"level":"Low"}""",
        Some("call-1"),
      ),
      InvocationContext(
        turn = 1,
        request = org.l4j.template.llm4s.core.ChatRequest(Nil),
        toolCall = ToolCall("search", "{}", Some("call-1")),
      ),
    ).unsafeRunSync()

    assertEquals(result, ToolResult.Text("scala:Low:backend"))
    assertEquals(definition.schema.parameters.required, Set("query", "level", "tags"))
  }

  enum ReviewLevel derives SchemaEncoder, ValueDecoder:
    case Low, High

  final case class Filters(
      region: String,
      active: Boolean,
  ) derives SchemaEncoder, ValueDecoder

  final case class SearchArgs(
      query: String,
      filters: Option[Filters],
      tags: List[String],
      level: ReviewLevel,
  ) derives SchemaEncoder, ValueDecoder
