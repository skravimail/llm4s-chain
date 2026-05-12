package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.streaming.StreamEvent

class OpenAiCompatStreamingBackendSpec extends FunSuite:

  test("stream decoder maps text deltas tool call deltas and completion markers") {
    val events = OpenAiStreamDecoder.decodeLine(
      """data: {"choices":[{"delta":{"content":"Hel","tool_calls":[{"id":"call-1","function":{"name":"lookup","arguments":"{\"term\":\"Scala\"}"}}]},"finish_reason":"tool_calls"}]}"""
    )

    assertEquals(events.collect { case StreamEvent.TextDelta(value) => value }, List("Hel"))
    assertEquals(
      events.collect { case StreamEvent.ToolCallDelta(id, name, args) => (id, name, args) },
      List((Some("call-1"), Some("lookup"), """{"term":"Scala"}""")),
    )
    assertEquals(
      events.collect { case StreamEvent.Completed(response) => response.finishReason },
      List(Some(FinishReason.ToolCalls)),
    )
  }

  test("streaming backend sends stream=true and decodes the emitted events") {
    final class RecordingTransport(lines: List[String]) extends OpenAiStreamingTransport[IO]:
      var capturedBody: Option[ujson.Value] = None

      override def stream(
          path: String,
          body: ujson.Value,
          headers: Map[String, String],
      ): Stream[IO, String] =
        capturedBody = Some(body)
        Stream.emits(lines).covary[IO]

    val transport = new RecordingTransport(
      List(
        """data: {"choices":[{"delta":{"content":"Hello"},"finish_reason":null}]}""",
        """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        "data: [DONE]",
      )
    )
    val backend = OpenAiCompatStreamingBackend[IO](
      OpenAiCompatConfig("https://example.test/v1", "secret", "gpt-stream"),
      transport,
    )

    val events = backend.stream(
      ChatRequest(messages = List(ChatMessage.UserMessage.from("Ping")))
    ).compile.toList.unsafeRunSync()

    assertEquals(transport.capturedBody.map(_("stream").bool), Some(true))
    assertEquals(events.collect { case StreamEvent.TextDelta(value) => value }, List("Hello"))
    assertEquals(events.collect { case StreamEvent.Completed(response) => response.finishReason }, List(Some(FinishReason.Stop)))
  }
