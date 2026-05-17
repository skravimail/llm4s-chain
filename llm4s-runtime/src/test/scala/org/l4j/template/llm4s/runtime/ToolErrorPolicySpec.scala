package org.l4j.template.llm4s.runtime

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema

class ToolErrorPolicySpec extends FunSuite:

  private def schema(n: String) = ToolSchema(n, n, JsonSchema.ObjectSchema(Map.empty))

  private def boom: ToolExecutor[IO] = new ToolExecutor[IO]:
    override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
      IO.raiseError(new RuntimeException("kaboom"))

  private def script(responses: List[ChatResponse]): RecordingBackend =
    RecordingBackend(responses)

  private def toolCall(name: String) =
    ChatResponse(
      ChatMessage.AiMessage(
        contents = Nil,
        toolCalls = List(ToolCall(name, "{}", Some(s"c-$name"))),
        finishReason = Some(FinishReason.ToolCalls),
      )
    )

  private val done = ChatResponse(ChatMessage.AiMessage.from("done"))

  test("SurfaceToModel: tool error becomes a JSON tool message and the model continues") {
    val backend = script(List(toolCall("explode"), done))
    val kit = ToolKit[IO](List(schema("explode")), Map("explode" -> boom))
    val runtime = AiRuntime[IO](
      backend,
      RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.SurfaceToModel),
    )

    val text = runtime.chat(None, "go", kit).unsafeRunSync()
    assertEquals(text, "done")
    // The error must have been threaded into the second request as a tool message.
    val toolMsg = backend.requests(1).messages.collectFirst { case t: ChatMessage.ToolResultMessage => t }
    assert(toolMsg.exists(_.result.isError))
    assert(toolMsg.exists(_.result.text.contains("kaboom")))
  }

  test("FailFast: tool error raises typed AiRuntimeError.ToolFailed") {
    val backend = script(List(toolCall("explode")))
    val kit = ToolKit[IO](List(schema("explode")), Map("explode" -> boom))
    val runtime = AiRuntime[IO](
      backend,
      RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.FailFast),
    )

    val err = intercept[AiRuntimeError.ToolFailed] {
      runtime.chat(None, "go", kit).unsafeRunSync()
    }
    assertEquals(err.toolName, "explode")
    assertEquals(err.underlying.getMessage, "kaboom")
    assertEquals(backend.requests.length, 1)
  }

  test("RetryOnce: a one-shot failure recovers on second attempt") {
    val program = for
      attempts <- Ref.of[IO, Int](0)
      flaky = new ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          attempts.updateAndGet(_ + 1).flatMap { n =>
            if n == 1 then IO.raiseError(RuntimeException("transient"))
            else IO.pure(ToolResult.Text("succeeded"))
          }
      kit = ToolKit[IO](List(schema("flaky")), Map("flaky" -> flaky))
      backend = script(List(toolCall("flaky"), done))
      runtime = AiRuntime[IO](
        backend,
        RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.RetryOnce),
      )
      text <- runtime.chat(None, "go", kit)
      total <- attempts.get
    yield (text, total, backend)

    val (text, total, backend) = program.unsafeRunSync()
    assertEquals(text, "done")
    assertEquals(total, 2)
    // After retry success, the tool message must report the successful result, not the error.
    val toolMsg = backend.requests(1).messages.collectFirst { case t: ChatMessage.ToolResultMessage => t }
    assertEquals(toolMsg.map(_.result.text), Some("succeeded"))
  }

  test("Unknown tool raises ToolMissing by default") {
    val backend = script(List(toolCall("ghost")))
    val kit = ToolKit.empty[IO]
    val runtime = AiRuntime[IO](backend, RuntimeConfig()) // default unknownToolPolicy = FailFast

    val err = intercept[AiRuntimeError.ToolMissing] {
      runtime.chat(None, "go", kit).unsafeRunSync()
    }
    assertEquals(err.toolName, "ghost")
  }

  test("Unknown tool can be surfaced to the model when configured") {
    val backend = script(List(toolCall("ghost"), done))
    val kit = ToolKit.empty[IO]
    val runtime = AiRuntime[IO](
      backend,
      RuntimeConfig(unknownToolPolicy = ToolErrorPolicy.SurfaceToModel),
    )

    val text = runtime.chat(None, "go", kit).unsafeRunSync()
    assertEquals(text, "done")
    val toolMsg = backend.requests(1).messages.collectFirst { case t: ChatMessage.ToolResultMessage => t }
    assert(toolMsg.exists(_.result.isError))
    assert(toolMsg.exists(_.result.text.contains("no such tool")))
  }

  private final case class RecordingBackend(
      scriptedResponses: List[ChatResponse]
  ) extends ChatBackend[IO]:
    @volatile private var remaining = scriptedResponses
    @volatile private var seen = Vector.empty[ChatRequest]

    def requests: Vector[ChatRequest] = seen

    override def chat(request: ChatRequest): IO[ChatResponse] =
      IO {
        seen = seen :+ request
        remaining match
          case head :: tail =>
            remaining = tail
            head
          case Nil =>
            // re-issue last; assume the model would have stopped, but if the
            // runtime keeps looping we want a clean test failure rather than
            // a hang.
            throw RuntimeException("no scripted responses remaining")
      }

  @annotation.unused private val _suppress = AiContent.Text("")
