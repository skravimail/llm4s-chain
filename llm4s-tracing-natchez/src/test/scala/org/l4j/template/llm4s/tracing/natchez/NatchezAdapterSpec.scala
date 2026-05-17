package org.l4j.template.llm4s.tracing.natchez

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.~>
import fs2.Stream
import java.net.URI
import munit.FunSuite
import natchez.Kernel
import natchez.Span
import natchez.Trace
import natchez.TraceValue
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.guardrails.GuardrailViolation
import org.l4j.template.llm4s.runtime.InvocationContext

/** Tests use a homemade in-memory `Trace[IO]` (`RecordingTrace`) that
  * captures every `put` / `attachError` call into a `Ref`. natchez ships
  * an `IOLocal`-based `Trace` plus several backends, but for adapter
  * coverage we only need to see that the listener wrote the right fields.
  */
class NatchezAdapterSpec extends FunSuite:

  test("runtime listener: chat events attach trace-id and event-name fields") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      given Trace[IO] = recordingTrace(buf)
      listener = NatchezRuntimeListener[IO]
      trace = TraceContext.of("trace-1", "span-A")
      _ <- listener.onChatStarted(trace, ChatRequest(List(ChatMessage.UserMessage.from("hi"))))
      _ <- listener.onChatCompleted(
        trace,
        ChatRequest(Nil),
        text = "done",
        turns = 2,
        durationNanos = 1234L,
      )
      fields <- buf.get
    yield fields

    val fields = program.unsafeRunSync()
    val events = fields.collect { case ("ai.event", TraceValue.StringValue(v)) => v }
    assertEquals(events, Vector("chat.started", "chat.completed"))
    assert(fields.contains(("ai.trace.id", TraceValue.StringValue("trace-1"))))
    assert(fields.contains(("ai.chat.turns", TraceValue.NumberValue(2))))
    assert(fields.contains(("ai.duration.ns", TraceValue.NumberValue(1234L))))
  }

  test("runtime listener: tool failed attaches error class + attempt + willRetry") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      errors <- Ref.of[IO, Vector[Throwable]](Vector.empty)
      given Trace[IO] = recordingTrace(buf, errors)
      listener = NatchezRuntimeListener[IO]
      ctx = InvocationContext(
        turn = 1,
        request = ChatRequest(Nil),
        toolCall = ToolCall("lookup", "{}"),
      )
      _ <- listener.onToolFailed(
        ToolCall("lookup", "{}"),
        ctx,
        new RuntimeException("kaboom"),
        attempt = 1,
        willRetry = true,
      )
      fields <- buf.get
      errs <- errors.get
    yield (fields, errs)

    val (fields, errs) = program.unsafeRunSync()
    assert(fields.contains(("ai.event", TraceValue.StringValue("tool.failed"))))
    assert(fields.contains(("ai.tool.attempt", TraceValue.NumberValue(1))))
    assert(fields.contains(("ai.tool.will_retry", TraceValue.StringValue("true"))))
    assert(fields.exists(_ == ("ai.error.message", TraceValue.StringValue("kaboom"))))
    assertEquals(errs.length, 1)
    assertEquals(errs.head.getMessage, "kaboom")
  }

  test("guardrail listener: input block attaches guardrail code + message") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      given Trace[IO] = recordingTrace(buf)
      listener = NatchezGuardrailListener[IO]
      _ <- listener.onInputBlocked(
        ChatRequest(List(ChatMessage.UserMessage.from("bad"))),
        GuardrailViolation("input.blocked", "no go"),
      )
      fields <- buf.get
    yield fields

    val fields = program.unsafeRunSync()
    assert(fields.contains(("ai.event", TraceValue.StringValue("guardrail.input.blocked"))))
    assert(fields.contains(("ai.guardrail.code", TraceValue.StringValue("input.blocked"))))
    assert(fields.contains(("ai.guardrail.message", TraceValue.StringValue("no go"))))
  }

  test("http listener: response attaches method, url, status, duration") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      given Trace[IO] = recordingTrace(buf)
      listener = NatchezHttpListener[IO]
      uri = sttp.model.Uri.unsafeParse("http://example/test")
      _ <- listener.onHttpResponse(sttp.model.Method.POST, uri, 200, 5000L)
      fields <- buf.get
    yield fields

    val fields = program.unsafeRunSync()
    assert(fields.contains(("ai.event", TraceValue.StringValue("http.response"))))
    assert(fields.contains(("http.method", TraceValue.StringValue("POST"))))
    assert(fields.contains(("http.status_code", TraceValue.NumberValue(200))))
    assert(fields.contains(("ai.duration.ns", TraceValue.NumberValue(5000L))))
  }

  test("http listener: failure attaches error and re-raises via attachError") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      errors <- Ref.of[IO, Vector[Throwable]](Vector.empty)
      given Trace[IO] = recordingTrace(buf, errors)
      listener = NatchezHttpListener[IO]
      uri = sttp.model.Uri.unsafeParse("http://example/x")
      _ <- listener.onHttpFailure(sttp.model.Method.GET, uri, new RuntimeException("dns nx"), 1L)
      errs <- errors.get
    yield errs

    val errs = program.unsafeRunSync()
    assertEquals(errs.length, 1)
    assertEquals(errs.head.getMessage, "dns nx")
  }

  test("workflow listener: agent succeeded attaches name + duration") {
    val program = for
      buf <- Ref.of[IO, Vector[(String, TraceValue)]](Vector.empty)
      given Trace[IO] = recordingTrace(buf)
      listener = NatchezWorkflowListener[IO]
      _ <- listener.onAgentStarted("writer", "topic")
      _ <- listener.onAgentSucceeded("writer", "draft", 9999L)
      fields <- buf.get
    yield fields

    val fields = program.unsafeRunSync()
    val events = fields.collect { case ("ai.event", TraceValue.StringValue(v)) => v }
    assertEquals(events, Vector("workflow.agent.started", "workflow.agent.succeeded"))
    assert(fields.contains(("ai.agent.name", TraceValue.StringValue("writer"))))
    assert(fields.contains(("ai.duration.ns", TraceValue.NumberValue(9999L))))
  }

  // Silence unused-warning gymnastics on imports brought in for typeclass scope.
  @annotation.unused private val _suppress = (
    ToolResult.Text(""),
    ToolSchema("x", "x", JsonSchema.ObjectSchema(Map.empty)),
  )

  // -- A tiny test-only Trace[IO] -------------------------------------------

  private def recordingTrace(
      buf: Ref[IO, Vector[(String, TraceValue)]],
      errors: Ref[IO, Vector[Throwable]] = Ref.unsafe[IO, Vector[Throwable]](Vector.empty),
  ): Trace[IO] = new Trace[IO] {
    override def put(fields: (String, TraceValue)*): IO[Unit] =
      buf.update(_ ++ fields.toVector)
    override def log(fields: (String, TraceValue)*): IO[Unit] =
      buf.update(_ ++ fields.toVector)
    override def log(event: String): IO[Unit] =
      buf.update(_ :+ (("event", TraceValue.StringValue(event))))
    override def attachError(err: Throwable, fields: (String, TraceValue)*): IO[Unit] =
      errors.update(_ :+ err) *> buf.update(_ ++ fields.toVector)
    override def kernel: IO[Kernel] = IO.pure(Kernel(Map.empty))
    override def spanR(name: String, options: Span.Options): Resource[IO, IO ~> IO] =
      Resource.pure(new (IO ~> IO) { def apply[A](fa: IO[A]): IO[A] = fa })
    override def span[A](name: String, options: Span.Options)(k: IO[A]): IO[A] = k
    override def traceId: IO[Option[String]] = IO.pure(None)
    override def traceUri: IO[Option[URI]] = IO.pure(None)
  }

  // Silence Stream unused-import warning under -Wunused.
  @annotation.unused private val _streamWarn: Stream[IO, Int] = Stream.empty
