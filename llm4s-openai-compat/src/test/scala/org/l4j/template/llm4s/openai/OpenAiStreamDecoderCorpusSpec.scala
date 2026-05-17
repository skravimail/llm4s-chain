package org.l4j.template.llm4s.openai

import munit.FunSuite
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.streaming.StreamEvent

/** Corpus of realistic SSE chunks captured from OpenAI-compatible providers.
  * Each chunk is fed through `OpenAiStreamDecoder.decodeLine` and asserted.
  *
  * Add more lines here when a provider's shape drifts — historically that is
  * exactly where the streaming decoder breaks silently. */
class OpenAiStreamDecoderCorpusSpec extends FunSuite:

  test("OpenAI: incremental text deltas before stop") {
    assertEquals(
      OpenAiStreamDecoder.decodeLine(
        """data: {"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}"""
      ),
      List(StreamEvent.TextDelta("Hel")),
    )
    assertEquals(
      OpenAiStreamDecoder.decodeLine(
        """data: {"choices":[{"delta":{"content":"lo, "},"finish_reason":null}]}"""
      ),
      List(StreamEvent.TextDelta("lo, ")),
    )
  }

  test("OpenAI: tool call delta streamed across chunks (id + name + partial args)") {
    val first = OpenAiStreamDecoder.decodeLine(
      """data: {"choices":[{"delta":{"tool_calls":[{"id":"c1","function":{"name":"lookup","arguments":"{\"q\":"}}]},"finish_reason":null}]}"""
    )
    val rest = OpenAiStreamDecoder.decodeLine(
      """data: {"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"\"scala\"}"}}]},"finish_reason":null}]}"""
    )

    assertEquals(
      first.collect { case StreamEvent.ToolCallDelta(id, name, args) => (id, name, args) },
      List((Some("c1"), Some("lookup"), """{"q":""")),
    )
    assertEquals(
      rest.collect { case StreamEvent.ToolCallDelta(id, name, args) => (id, name, args) },
      List((None, None, """"scala"}""")),
    )
  }

  test("OpenAI: empty deltas with finish_reason=stop emit Completed") {
    val events = OpenAiStreamDecoder.decodeLine(
      """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
    )
    assertEquals(
      events.collect { case StreamEvent.Completed(r) => r.finishReason },
      List(Some(FinishReason.Stop)),
    )
  }

  test("OpenAI: terminator [DONE] is a no-op (no events)") {
    assertEquals(OpenAiStreamDecoder.decodeLine("data: [DONE]"), Nil)
  }

  test("OpenAI: keepalive / blank lines are ignored") {
    assertEquals(OpenAiStreamDecoder.decodeLine(""), Nil)
    assertEquals(OpenAiStreamDecoder.decodeLine(": ping"), Nil)
  }

  test("OpenAI: content_filter finish reason surfaces") {
    val events = OpenAiStreamDecoder.decodeLine(
      """data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""
    )
    assertEquals(
      events.collect { case StreamEvent.Completed(r) => r.finishReason },
      List(Some(FinishReason.ContentFilter)),
    )
  }

  test("OpenAI: malformed JSON does not throw — yields no events") {
    assertEquals(OpenAiStreamDecoder.decodeLine("data: {not-json"), Nil)
  }
