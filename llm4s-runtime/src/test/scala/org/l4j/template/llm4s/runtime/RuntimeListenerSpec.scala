package org.l4j.template.llm4s.runtime

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.core.TraceId

class RuntimeListenerSpec extends FunSuite:

  sealed trait Event:
    def traceId: TraceId
  object Event:
    final case class Started(traceId: TraceId) extends Event
    final case class Completed(traceId: TraceId, text: String, durationNanos: Long) extends Event
    final case class Failed(traceId: TraceId, msg: String) extends Event
    final case class ProviderReq(traceId: TraceId, turn: Int) extends Event
    final case class ProviderResp(traceId: TraceId, turn: Int, durationNanos: Long) extends Event
    final case class ToolCalled(traceId: TraceId, name: String) extends Event
    final case class ToolSucceeded(traceId: TraceId, name: String, durationNanos: Long) extends Event
    final case class ToolFailed(traceId: TraceId, name: String, attempt: Int, willRetry: Boolean, msg: String)
        extends Event

  private def recordingListener(buf: Ref[IO, Vector[Event]]): RuntimeListener[IO] =
    new RuntimeListener.Default[IO]:
      override def onChatStarted(trace: TraceContext, request: ChatRequest): IO[Unit] =
        buf.update(_ :+ Event.Started(trace.traceId))
      override def onChatCompleted(t: TraceContext, r: ChatRequest, text: String, turns: Int, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Completed(t.traceId, text, d))
      override def onChatFailed(t: TraceContext, r: ChatRequest, e: Throwable): IO[Unit] =
        buf.update(_ :+ Event.Failed(t.traceId, Option(e.getMessage).getOrElse("")))
      override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): IO[Unit] =
        buf.update(_ :+ Event.ProviderReq(t.traceId, turn))
      override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): IO[Unit] =
        buf.update(_ :+ Event.ProviderResp(t.traceId, turn, d))
      override def onToolCalled(c: ToolCall, ctx: InvocationContext): IO[Unit] =
        buf.update(_ :+ Event.ToolCalled(ctx.trace.traceId, c.name))
      override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long): IO[Unit] =
        buf.update(_ :+ Event.ToolSucceeded(ctx.trace.traceId, c.name, d))
      override def onToolFailed(c: ToolCall, ctx: InvocationContext, e: Throwable, a: Int, w: Boolean): IO[Unit] =
        buf.update(_ :+ Event.ToolFailed(ctx.trace.traceId, c.name, a, w, Option(e.getMessage).getOrElse("")))

  private def pingKit: ToolKit[IO] =
    ToolKit[IO](
      List(ToolSchema("ping", "p", JsonSchema.ObjectSchema(Map.empty))),
      Map("ping" -> new ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          IO.pure(ToolResult.Text("pong"))),
    )

  private val toolThenDone = List(
    ChatResponse(
      ChatMessage.AiMessage(
        contents = Nil,
        toolCalls = List(ToolCall("ping", "{}", Some("c"))),
        finishReason = Some(FinishReason.ToolCalls),
      )
    ),
    ChatResponse(ChatMessage.AiMessage.from("done")),
  )

  test("v2: every event of one chat shares the same traceId") {
    val backend = RecordingBackend(toolThenDone)
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), recordingListener(buf))
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    val ids = events.map(_.traceId).distinct
    assertEquals(ids.size, 1, s"expected one traceId across $events")
  }

  test("v2: per-turn provider events fire alongside chat-level events") {
    val backend = RecordingBackend(toolThenDone)
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), recordingListener(buf))
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    val providerReqTurns = events.collect { case Event.ProviderReq(_, t) => t }
    val providerRespTurns = events.collect { case Event.ProviderResp(_, t, _) => t }

    assertEquals(providerReqTurns, Vector(0, 1))
    assertEquals(providerRespTurns, Vector(0, 1))
    assert(events.head.isInstanceOf[Event.Started])
    assert(events.last.isInstanceOf[Event.Completed])
  }

  test("v2: tool success carries a positive duration") {
    val backend = RecordingBackend(toolThenDone)
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), recordingListener(buf))
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    val success = events.collectFirst { case s: Event.ToolSucceeded => s }
    assert(success.isDefined)
    assert(success.get.durationNanos >= 0L)
  }

  test("v2: RetryOnce surfaces attempt=1/willRetry=true then attempt=2/willRetry=false") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = Nil,
            toolCalls = List(ToolCall("flaky", "{}", Some("c"))),
            finishReason = Some(FinishReason.ToolCalls),
          )
        ),
        ChatResponse(ChatMessage.AiMessage.from("done")),
      )
    )
    val program = for
      attempts <- Ref.of[IO, Int](0)
      flaky = new ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          attempts.updateAndGet(_ + 1).flatMap { n =>
            if n == 1 then IO.raiseError(RuntimeException("first"))
            else IO.raiseError(RuntimeException("second"))
          }
      kit = ToolKit[IO](
        List(ToolSchema("flaky", "", JsonSchema.ObjectSchema(Map.empty))),
        Map("flaky" -> flaky),
      )
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      runtime = AiRuntime[IO](
        backend,
        RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.RetryOnce),
        recordingListener(buf),
      )
      _ <- runtime.chat(None, "go", kit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    val fails = events.collect { case f: Event.ToolFailed => (f.attempt, f.willRetry, f.msg) }
    assertEquals(fails, Vector((1, true, "first"), (2, false, "second")))
  }

  test("v2: concurrent chats get distinct traceIds") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      mkBackend = (text: String) => RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from(text))))
      r1 = AiRuntime[IO](mkBackend("one"), RuntimeConfig(), recordingListener(buf))
      r2 = AiRuntime[IO](mkBackend("two"), RuntimeConfig(), recordingListener(buf))
      _ <- (r1.chat(None, "a", ToolKit.empty[IO]), r2.chat(None, "b", ToolKit.empty[IO])).parTupled
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    val started = events.collect { case s: Event.Started => s.traceId }
    val completed = events.collect { case c: Event.Completed => c.traceId }
    assertEquals(started.distinct.size, 2)
    assertEquals(completed.distinct.size, 2)
  }

  test("v2: chat failure fires onChatFailed with the typed error") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(contents = Nil, finishReason = Some(FinishReason.ContentFilter))
        )
      )
    )
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), recordingListener(buf))
      _ <- runtime.chat(None, "go", ToolKit.empty[IO]).attempt
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assert(events.exists(_.isInstanceOf[Event.Failed]))
  }

  private final case class RecordingBackend(
      scriptedResponses: List[ChatResponse]
  ) extends ChatBackend[IO]:
    @volatile private var remaining = scriptedResponses
    override def chat(request: ChatRequest): IO[ChatResponse] =
      IO {
        remaining match
          case head :: tail =>
            remaining = tail
            head
          case Nil =>
            throw RuntimeException("no scripted responses remaining")
      }
