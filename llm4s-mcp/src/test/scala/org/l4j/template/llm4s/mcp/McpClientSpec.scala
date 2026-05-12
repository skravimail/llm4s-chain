package org.l4j.template.llm4s.mcp

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.InvocationContext
import upickle.default.*

class McpClientSpec extends FunSuite:

  test("client lists all MCP tools across paginated responses") {
    val program = for
      transport <- RecordingTransport.create(
        List(
          ujson.Obj(
            "tools" -> ujson.Arr(
              toolJson("echo", "Echoes text")
            ),
            "nextCursor" -> "page-2",
          ),
          ujson.Obj(
            "tools" -> ujson.Arr(
              toolJson("lookup", "Looks up data")
            )
          ),
        )
      )
      tools <- McpClient[IO](transport).listAllTools
      calls <- transport.calls
    yield (tools, calls)

    val (tools, calls) = program.unsafeRunSync()

    assertEquals(tools.map(_.name), List("echo", "lookup"))
    assertEquals(calls.map(_._1), List("tools/list", "tools/list"))
    assertEquals(calls(1)._2.flatMap(_.obj.value.get("cursor")).flatMap(_.strOpt), Some("page-2"))
  }

  test("tool provider adapts MCP tools into native ToolKit executors") {
    val program = for
      transport <- RecordingTransport.create(
        List(
          ujson.Obj("tools" -> ujson.Arr(toolJson("echo", "Echoes text"))),
          ujson.Obj(
            "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "echo:hi")),
            "isError" -> false,
          ),
        )
      )
      toolKit <- McpToolProvider(McpClient[IO](transport)).toolKit
      result <- toolKit.executors("echo").execute(
        ToolCall("echo", """{"message":"hi"}"""),
        InvocationContext(1, ChatRequest(Nil), ToolCall("echo", """{"message":"hi"}""")),
      )
      calls <- transport.calls
    yield (toolKit, result, calls)

    val (toolKit, result, calls) = program.unsafeRunSync()

    assertEquals(toolKit.schemas.map(_.name), List("echo"))
    assertEquals(toolKit.schemas.head.parameters.properties("message"), JsonSchema.StringSchema(Some("Message to echo")))
    assertEquals(result, ToolResult.Content(List(org.l4j.template.llm4s.core.AiContent.Text("echo:hi"))))
    assertEquals(calls.last._1, "tools/call")
    assertEquals(calls.last._2.flatMap(_.obj.value.get("name")).flatMap(_.strOpt), Some("echo"))
  }

  test("tool call result prefers structured content when present") {
    val result = McpToolCallResult(
      content = List(McpContent.Text("ignored")),
      structuredContent = Some(ujson.Obj("ok" -> true)),
    ).toToolResult

    assertEquals(result, ToolResult.StructuredJson("""{"ok":true}"""))
  }

  test("stdio transport writes JSON-RPC requests and decodes response results") {
    val program = for
      writes <- Ref.of[IO, List[String]](Nil)
      reads <- Ref.of[IO, List[String]](
        List("""{"jsonrpc":"2.0","id":1,"result":{"ok":true}}""")
      )
      transport <- StdioMcpTransport.create[IO](
        readLine = reads.modify {
          case head :: tail => tail -> head
          case Nil          => Nil -> """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"empty"}}"""
        },
        writeLine = line => writes.update(_ :+ line),
      )
      result <- transport.request("tools/list", None)
      written <- writes.get
    yield (result, written)

    val (result, written) = program.unsafeRunSync()

    assertEquals(result, ujson.Obj("ok" -> true))
    val writtenJson = read[ujson.Value](written.head)
    assertEquals(writtenJson("method").str, "tools/list")
  }

private def toolJson(name: String, description: String): ujson.Obj =
  ujson.Obj(
    "name" -> name,
    "description" -> description,
    "inputSchema" -> ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "message" -> ujson.Obj(
          "type" -> "string",
          "description" -> "Message to echo",
        )
      ),
      "required" -> ujson.Arr("message"),
    ),
  )

private final class RecordingTransport(
    state: Ref[IO, (List[(String, Option[ujson.Value])], List[ujson.Value])]
) extends McpTransport[IO]:

  def calls: IO[List[(String, Option[ujson.Value])]] =
    state.get.map(_._1)

  override def request(method: String, params: Option[ujson.Value]): IO[ujson.Value] =
    state.modify { case (calls, responses) =>
      responses match
        case head :: tail => ((calls :+ (method -> params), tail), head)
        case Nil          => ((calls :+ (method -> params), Nil), ujson.Obj())
    }

private object RecordingTransport:
  def create(responses: List[ujson.Value]): IO[RecordingTransport] =
    Ref.of[IO, (List[(String, Option[ujson.Value])], List[ujson.Value])]((Nil, responses)).map(new RecordingTransport(_))
