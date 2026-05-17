package org.l4j.template.llm4s.runtime

import cats.effect.IO
import munit.FunSuite
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema

class ToolKitSpec extends FunSuite:

  private def schema(n: String) = ToolSchema(n, n, JsonSchema.ObjectSchema(Map.empty))

  private def exec(label: String): ToolExecutor[IO] =
    new ToolExecutor[IO]:
      override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
        IO.pure(ToolResult.Text(label))

  test("legacy constructor rejects schemas without matching executors") {
    val ex = intercept[IllegalArgumentException] {
      ToolKit[IO](
        schemas = List(schema("a"), schema("b")),
        executors = Map("a" -> exec("a")),
      )
    }
    assert(ex.getMessage.contains("schemas without executors"))
    assert(ex.getMessage.contains("b"))
  }

  test("legacy constructor rejects executors without matching schemas") {
    val ex = intercept[IllegalArgumentException] {
      ToolKit[IO](
        schemas = List(schema("a")),
        executors = Map("a" -> exec("a"), "rogue" -> exec("rogue")),
      )
    }
    assert(ex.getMessage.contains("executors without schemas"))
    assert(ex.getMessage.contains("rogue"))
  }

  test("legacy constructor rejects duplicate schema names") {
    val ex = intercept[IllegalArgumentException] {
      ToolKit[IO](
        schemas = List(schema("a"), schema("a")),
        executors = Map("a" -> exec("a")),
      )
    }
    assert(ex.getMessage.contains("duplicate schema names"))
  }

  test("ToolKit.of pairs schema and executor by name") {
    val kit = ToolKit.of(ToolEntry(schema("a"), exec("a")), ToolEntry(schema("b"), exec("b")))
    assertEquals(kit.size, 2)
    assertEquals(kit.names, Set("a", "b"))
    assertEquals(kit.schemas.map(_.name).sorted, List("a", "b"))
    assert(kit.executors.contains("a"))
    assert(kit.executors.contains("b"))
  }

  test("ToolKit.fromPairs builds a balanced kit") {
    val kit = ToolKit.fromPairs[IO](schema("a") -> exec("a"))
    assertEquals(kit.names, Set("a"))
  }

  test("++ merges entries with right-bias on name clash") {
    val a = ToolKit.fromPairs[IO](schema("x") -> exec("first"))
    val b = ToolKit.fromPairs[IO](schema("x") -> exec("second"), schema("y") -> exec("y"))
    val merged = a ++ b

    assertEquals(merged.names, Set("x", "y"))
    // right side wins on x
    import cats.effect.unsafe.implicits.global
    val xResult =
      merged.executors("x").execute(ToolCall("x", "{}"), InvocationContext(0, null, ToolCall("x", "{}"))).unsafeRunSync()
    assertEquals(xResult.text, "second")
  }

  test("withEntry adds or replaces a single entry") {
    val empty = ToolKit.empty[IO]
    val withA = empty.withEntry(ToolEntry(schema("a"), exec("a1")))
    val withA2 = withA.withEntry(ToolEntry(schema("a"), exec("a2")))
    assertEquals(withA2.size, 1)
    import cats.effect.unsafe.implicits.global
    val res =
      withA2.executors("a").execute(ToolCall("a", "{}"), InvocationContext(0, null, ToolCall("a", "{}"))).unsafeRunSync()
    assertEquals(res.text, "a2")
  }
