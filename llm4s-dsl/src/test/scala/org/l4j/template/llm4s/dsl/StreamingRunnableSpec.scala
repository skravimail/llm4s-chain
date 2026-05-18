package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.streaming.StreamEvent
import org.l4j.template.llm4s.streaming.StreamingChatBackend

class StreamingRunnableSpec extends FunSuite:

  test("prompt template composes with the streaming chat model") {
    val backend = new RecordingStreamingBackend(request =>
      Stream.emits[IO, StreamEvent](
        List(
          StreamEvent.TextDelta(request.messages.last.text),
          StreamEvent.TextDelta("!"),
        )
      )
    )

    val chain =
      PromptTemplate
        .user[IO, String](system = Some("Be concise."))(topic => s"Explain $topic")
        .andThenStream(StreamingChatModel[IO])

    val text =
      chain
        .stream("opaque types", context(backend))
        .collect { case StreamEvent.TextDelta(value) => value }
        .compile
        .string
        .unsafeRunSync()

    assertEquals(text, "Explain opaque types!")
    assertEquals(backend.requests.last.messages.map(_.text), List("Be concise.", "Explain opaque types"))
  }

  test("streaming chat model emits listener completion events through RunContext") {
    val backend = new RecordingStreamingBackend(_ =>
      Stream.emits[IO, StreamEvent](
        List(
          StreamEvent.TextDelta("a"),
          StreamEvent.TextDelta("b"),
          StreamEvent.TextDelta("c"),
        )
      )
    )

    val program = for
      seen <- Ref.of[IO, Vector[(String, Long)]](Vector.empty)
      listener = new RuntimeListener.Default[IO]:
        override def onStreamStarted(trace: TraceContext, request: ChatRequest): IO[Unit] =
          seen.update(_ :+ ("started", 0L))
        override def onStreamCompleted(
            trace: TraceContext,
            request: ChatRequest,
            eventCount: Long,
            durationNanos: Long,
        ): IO[Unit] =
          seen.update(_ :+ ("completed", eventCount))
      _ <- PromptTemplate
        .user[IO, String]()(identity)
        .andThenStream(StreamingChatModel[IO])
        .stream(
          "go",
          context(backend, listener),
        )
        .compile
        .drain
      events <- seen.get
    yield events

    val events = program.unsafeRunSync()

    assertEquals(events, Vector(("started", 0L), ("completed", 3L)))
  }

  private def context(
      streamingBackend: StreamingChatBackend[IO],
      runtimeListener: RuntimeListener[IO] = RuntimeListener.noop[IO],
  ): RunContext[IO] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.pure(ChatResponse(ChatMessage.AiMessage.from(request.messages.last.text)))

    RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = runtimeListener,
      streamingBackend0 = Some(streamingBackend),
    )

  private final class RecordingStreamingBackend(
      f: ChatRequest => Stream[IO, StreamEvent]
  ) extends StreamingChatBackend[IO]:
    private var seen = Vector.empty[ChatRequest]

    def requests: Vector[ChatRequest] = seen

    override def stream(request: ChatRequest): Stream[IO, StreamEvent] =
      seen = seen :+ request
      f(request)
