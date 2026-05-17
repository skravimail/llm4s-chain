package org.l4j.template.llm4s.mcp

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Deferred
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel.*
import munit.FunSuite
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.InvocationContext
import sttp.client3.SttpBackend
import sttp.client3.testing.SttpBackendStub
import sttp.monad.MonadError as SttpMonadError
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

  test("tool call result converts MCP resource content into native file content") {
    val program = for
      transport <- RecordingTransport.create(
        List(
          ujson.Obj(
            "content" -> ujson.Arr(
              ujson.Obj(
                "type" -> "resource",
                "resource" -> ujson.Obj(
                  "uri" -> "file:///brief.pdf",
                  "mimeType" -> "application/pdf",
                  "blob" -> "file-bytes",
                ),
              )
            )
          )
        )
      )
      result <- McpClient[IO](transport).callTool("read_file", "{}")
    yield result.toToolResult

    assertEquals(
      program.unsafeRunSync(),
      ToolResult.Content(List(AiContent.File("file-bytes", "application/pdf", Some("file:///brief.pdf")))),
    )
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

  test("stdio transport serializes concurrent requests on one shared stream") {
    val program = for
      writes <- Ref.of[IO, List[String]](Nil)
      responses <- Ref.of[IO, List[String]](
        List(
          """{"jsonrpc":"2.0","id":1,"result":{"ok":"first"}}""",
          """{"jsonrpc":"2.0","id":2,"result":{"ok":"second"}}""",
        )
      )
      gate <- Deferred[IO, Unit]
      transport <- StdioMcpTransport.create[IO](
        readLine = gate.get *> responses.modify {
          case head :: tail => tail -> head
          case Nil          => Nil -> """{"jsonrpc":"2.0","id":999,"error":{"code":-32000,"message":"empty"}}"""
        },
        writeLine = line => writes.update(_ :+ line),
      )
      fibers <- (
        transport.request("tools/list", None),
        transport.request("tools/list", Some(ujson.Obj("cursor" -> "page-2"))),
      ).parTupled.start
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(150).millis)
      beforeRelease <- writes.get
      _ <- gate.complete(()).void
      _ <- fibers.joinWithNever
    yield beforeRelease

    val beforeRelease = program.unsafeRunSync()
    assertEquals(beforeRelease.length, 1)
  }

  test("http transport rejects mismatched response ids") {
    val program = for
      transport <- HttpMcpTransport.create[IO](
        sttp.model.Uri.unsafeParse("https://example.test/mcp"),
        stubHttpBackend("""{"jsonrpc":"2.0","id":999,"result":{"ok":true}}"""),
      )
      result <- transport.request("tools/list", None).attempt
    yield result

    val result = program.unsafeRunSync()
    assert(result.left.exists(_.getMessage.contains("did not match request id 1")))
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

private def stubHttpBackend(body: String): SttpBackend[IO, Any] =
  SttpBackendStub[IO, Any](sttpMonadError).whenAnyRequest.thenRespond(body)

private val sttpMonadError: SttpMonadError[IO] =
  new SttpMonadError[IO]:
    override def unit[T](t: T): IO[T] = IO.pure(t)
    override def map[T, T2](fa: IO[T])(f: T => T2): IO[T2] = fa.map(f)
    override def flatMap[T, T2](fa: IO[T])(f: T => IO[T2]): IO[T2] = fa.flatMap(f)
    override def error[T](t: Throwable): IO[T] = IO.raiseError(t)
    override def handleWrappedError[T](rt: IO[T])(h: PartialFunction[Throwable, IO[T]]): IO[T] =
      rt.handleErrorWith(error => h.applyOrElse(error, (_: Throwable) => IO.raiseError(error)))
    override def ensure[T](f: IO[T], e: => IO[Unit]): IO[T] =
      f.guarantee(e)
