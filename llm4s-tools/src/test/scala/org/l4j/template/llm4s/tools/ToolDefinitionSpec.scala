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

  test("derives ToolDef yields schema and decoder from one clause") {
    val td = summon[ToolDef[Greet]]

    val obj = td.schema.asInstanceOf[JsonSchema.ObjectSchema]
    assertEquals(obj.required, Set("name", "loud"))

    assertEquals(
      td.decoder.decode(ujson.read("""{"name":"Ada","loud":true}""")),
      Right(Greet("Ada", true)),
    )
  }

  test("ToolDefinition.fromArgs accepts derived ToolDef and decodes invocations") {
    val definition = ToolDefinition.fromArgs[IO, Greet](
      name = "greet",
      description = "Greet a user",
    ) { args =>
      IO.pure(ToolResult.Text(s"hi ${args.name}${if args.loud then "!" else ""}"))
    }

    val result = definition.executor.execute(
      ToolCall("greet", """{"name":"Ada","loud":true}""", Some("c1")),
      InvocationContext(0, org.l4j.template.llm4s.core.ChatRequest(Nil), ToolCall("greet", "{}", Some("c1"))),
    ).unsafeRunSync()

    assertEquals(result, ToolResult.Text("hi Ada!"))
    assertEquals(definition.schema.name, "greet")
    assertEquals(definition.schema.parameters.required, Set("name", "loud"))
  }

  test("ToolDefinition.fromArgs returns structured json error on bad args") {
    val definition = ToolDefinition.fromArgs[IO, Greet]("greet", "g") { _ =>
      IO.pure(ToolResult.Text("never"))
    }

    val result = definition.executor.execute(
      ToolCall("greet", """{"name":"Ada"}""", Some("c1")),
      InvocationContext(0, org.l4j.template.llm4s.core.ChatRequest(Nil), ToolCall("greet", "{}", Some("c1"))),
    ).unsafeRunSync()

    assert(result.isError)
    val parsed = ujson.read(result.text)
    assertEquals(parsed.obj.contains("error"), true)
    assertEquals(parsed("error").str.contains("loud"), true)
  }

  final case class Greet(name: String, loud: Boolean) derives ToolDef

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
