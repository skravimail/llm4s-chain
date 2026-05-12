package org.l4j.template.llm4s.streaming

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatRequest

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
