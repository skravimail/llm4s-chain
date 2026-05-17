package org.l4j.template.llm4s.streaming

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.core.TraceId
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeListener

class StreamingAiRuntimeSpec extends FunSuite:

  test("streaming runtime builds the initial request and collects text deltas") {
    val backend = RecordingStreamingBackend { _ =>
      Stream.emits[IO, StreamEvent](
        List(
          StreamEvent.TextDelta("Hello"),
          StreamEvent.TextDelta(", "),
          StreamEvent.TextDelta("streaming"),
        )
      )
    }

    val runtime = StreamingAiRuntime[IO](backend)
    val text = runtime.stream(Some("Be concise"), "Say hello").collectText.unsafeRunSync()

    assertEquals(text, "Hello, streaming")
    assertEquals(backend.requests.head.messages.map(_.text), List("Be concise", "Say hello"))
  }

  test("streaming listener observes onStreamStarted + onStreamCompleted with event count") {
    val backend = RecordingStreamingBackend { _ =>
      Stream.emits[IO, StreamEvent](
        List(
          StreamEvent.TextDelta("A"),
          StreamEvent.TextDelta("B"),
          StreamEvent.TextDelta("C"),
        )
      )
    }

    sealed trait Event
    final case class Started(traceId: TraceId) extends Event
    final case class Completed(traceId: TraceId, n: Long) extends Event
    final case class Failed(traceId: TraceId, n: Long, msg: String) extends Event

    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def onStreamStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
          buf.update(_ :+ Started(t.traceId))
        override def onStreamCompleted(t: TraceContext, r: ChatRequest, n: Long, d: Long): IO[Unit] =
          buf.update(_ :+ Completed(t.traceId, n))
        override def onStreamFailed(t: TraceContext, r: ChatRequest, e: Throwable, n: Long, d: Long): IO[Unit] =
          buf.update(_ :+ Failed(t.traceId, n, Option(e.getMessage).getOrElse("")))
      runtime = StreamingAiRuntime[IO](backend, listener)
      _ <- runtime.stream(None, "go").events.compile.drain
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events.size, 2)
    assert(events.head.isInstanceOf[Started])
    val Completed(_, n) = events.last: @unchecked
    assertEquals(n, 3L)
  }

  test("streaming listener fires onStreamFailed when the stream errors") {
    val backend = RecordingStreamingBackend { _ =>
      Stream
        .emits[IO, StreamEvent](List(StreamEvent.TextDelta("A")))
        ++ Stream.raiseError[IO](new RuntimeException("provider hung up"))
    }

    val program = for
      failures <- Ref.of[IO, Vector[(Long, String)]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def onStreamFailed(t: TraceContext, r: ChatRequest, e: Throwable, n: Long, d: Long): IO[Unit] =
          failures.update(_ :+ ((n, Option(e.getMessage).getOrElse(""))))
      runtime = StreamingAiRuntime[IO](backend, listener)
      _ <- runtime.stream(None, "go").events.compile.drain.attempt
      fs <- failures.get
    yield fs

    val fs = program.unsafeRunSync()
    assertEquals(fs.size, 1)
    assertEquals(fs.head._1, 1L)
    assert(fs.head._2.contains("provider hung up"))
  }

  // Make the unused-import warnings happy: these symbols are referenced only
  // to keep the listener Default override list in scope for compilation.
  @annotation.unused private val _suppress = (
    ChatResponse(org.l4j.template.llm4s.core.ChatMessage.AiMessage.from("")),
    ToolCall("x", "{}"),
    ToolResult.Text(""),
    InvocationContext(0, ChatRequest(Nil), ToolCall("x", "{}")),
  )

  private final class RecordingStreamingBackend(
      f: ChatRequest => Stream[IO, StreamEvent]
  ) extends StreamingChatBackend[IO]:
    private var seen = Vector.empty[ChatRequest]

    def requests: Vector[ChatRequest] = seen

    override def stream(request: ChatRequest): Stream[IO, StreamEvent] =
      seen = seen :+ request
      f(request)

  private object RecordingStreamingBackend:
    def apply(f: ChatRequest => Stream[IO, StreamEvent]): RecordingStreamingBackend =
      new RecordingStreamingBackend(f)
