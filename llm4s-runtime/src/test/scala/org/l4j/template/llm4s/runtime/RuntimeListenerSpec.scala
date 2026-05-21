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

  // -- Span bracket tests ---------------------------------------------------

  test("span brackets: spanChat wraps onChatStarted and onChatCompleted") {
    sealed trait E
    object E:
      case object ChatOpen      extends E
      case object ChatClose     extends E
      case object Started       extends E
      case object Completed     extends E

    val program = for
      buf <- Ref.of[IO, Vector[E]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def spanChat[A](t: TraceContext, r: ChatRequest)(use: IO[A]): IO[A] =
          buf.update(_ :+ E.ChatOpen) >> use <* buf.update(_ :+ E.ChatClose)
        override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
          buf.update(_ :+ E.Started)
        override def onChatCompleted(t: TraceContext, r: ChatRequest, x: String, n: Int, d: Long): IO[Unit] =
          buf.update(_ :+ E.Completed)
      backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("hi"))))
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", ToolKit.empty[IO])
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events, Vector(E.ChatOpen, E.Started, E.Completed, E.ChatClose))
  }

  test("span brackets: spanProviderCall is nested inside spanChat per turn") {
    sealed trait E
    object E:
      case object ChatOpen                extends E
      case object ChatClose               extends E
      case class ProviderOpen(turn: Int)  extends E
      case class ProviderClose(turn: Int) extends E
      case class ProviderReq(turn: Int)   extends E
      case class ProviderResp(turn: Int)  extends E

    val program = for
      buf <- Ref.of[IO, Vector[E]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def spanChat[A](t: TraceContext, r: ChatRequest)(use: IO[A]): IO[A] =
          buf.update(_ :+ E.ChatOpen) >> use <* buf.update(_ :+ E.ChatClose)
        override def spanProviderCall[A](t: TraceContext, turn: Int, r: ChatRequest)(use: IO[A]): IO[A] =
          buf.update(_ :+ E.ProviderOpen(turn)) >> use <* buf.update(_ :+ E.ProviderClose(turn))
        override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): IO[Unit] =
          buf.update(_ :+ E.ProviderReq(turn))
        override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): IO[Unit] =
          buf.update(_ :+ E.ProviderResp(turn))
      backend = RecordingBackend(toolThenDone)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    // Each ProviderOpen/Close must bracket the matching ProviderReq/Resp
    assert(events.indexOf(E.ChatOpen) < events.indexOf(E.ProviderOpen(0)))
    assert(events.indexOf(E.ProviderOpen(0)) < events.indexOf(E.ProviderReq(0)))
    assert(events.indexOf(E.ProviderResp(0)) < events.indexOf(E.ProviderClose(0)))
    assert(events.indexOf(E.ProviderClose(0)) < events.indexOf(E.ProviderOpen(1)))
    assert(events.indexOf(E.ProviderOpen(1)) < events.indexOf(E.ProviderReq(1)))
    assert(events.indexOf(E.ProviderResp(1)) < events.indexOf(E.ProviderClose(1)))
    assert(events.indexOf(E.ProviderClose(1)) < events.indexOf(E.ChatClose))
  }

  test("span brackets: spanToolCall wraps onToolCalled and onToolSucceeded") {
    sealed trait E
    object E:
      case class ToolOpen(name: String)      extends E
      case class ToolClose(name: String)     extends E
      case class ToolCalled(name: String)    extends E
      case class ToolSucceeded(name: String) extends E

    val program = for
      buf <- Ref.of[IO, Vector[E]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def spanToolCall[A](c: ToolCall, ctx: InvocationContext)(use: IO[A]): IO[A] =
          buf.update(_ :+ E.ToolOpen(c.name)) >> use <* buf.update(_ :+ E.ToolClose(c.name))
        override def onToolCalled(c: ToolCall, ctx: InvocationContext): IO[Unit] =
          buf.update(_ :+ E.ToolCalled(c.name))
        override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long): IO[Unit] =
          buf.update(_ :+ E.ToolSucceeded(c.name))
      backend = RecordingBackend(toolThenDone)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events, Vector(
      E.ToolOpen("ping"),
      E.ToolCalled("ping"),
      E.ToolSucceeded("ping"),
      E.ToolClose("ping"),
    ))
  }

  test("span brackets: full nesting order is chat > provider > tool") {
    val program = for
      buf <- Ref.of[IO, Vector[String]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def spanChat[A](t: TraceContext, r: ChatRequest)(use: IO[A]): IO[A] =
          buf.update(_ :+ "chat.open") >> use <* buf.update(_ :+ "chat.close")
        override def spanProviderCall[A](t: TraceContext, turn: Int, r: ChatRequest)(use: IO[A]): IO[A] =
          buf.update(_ :+ s"provider.open[$turn]") >> use <* buf.update(_ :+ s"provider.close[$turn]")
        override def spanToolCall[A](c: ToolCall, ctx: InvocationContext)(use: IO[A]): IO[A] =
          buf.update(_ :+ s"tool.open[${c.name}]") >> use <* buf.update(_ :+ s"tool.close[${c.name}]")
        override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
          buf.update(_ :+ "chat.started")
        override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): IO[Unit] =
          buf.update(_ :+ s"provider.req[$turn]")
        override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): IO[Unit] =
          buf.update(_ :+ s"provider.resp[$turn]")
        override def onToolCalled(c: ToolCall, ctx: InvocationContext): IO[Unit] =
          buf.update(_ :+ s"tool.called[${c.name}]")
        override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long): IO[Unit] =
          buf.update(_ :+ s"tool.succeeded[${c.name}]")
        override def onChatCompleted(t: TraceContext, r: ChatRequest, x: String, n: Int, d: Long): IO[Unit] =
          buf.update(_ :+ "chat.completed")
      backend = RecordingBackend(toolThenDone)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", pingKit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events, Vector(
      "chat.open",
      "chat.started",
      "provider.open[0]",
      "provider.req[0]",
      "provider.resp[0]",
      "provider.close[0]",
      "tool.open[ping]",
      "tool.called[ping]",
      "tool.succeeded[ping]",
      "tool.close[ping]",
      "provider.open[1]",
      "provider.req[1]",
      "provider.resp[1]",
      "provider.close[1]",
      "chat.completed",
      "chat.close",
    ))
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
