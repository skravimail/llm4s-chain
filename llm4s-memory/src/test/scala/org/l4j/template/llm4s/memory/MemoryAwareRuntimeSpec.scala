package org.l4j.template.llm4s.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.runtime.AiRuntime

class MemoryAwareRuntimeSpec extends FunSuite:

  test("MemoryAwareRuntime persists conversational history across calls") {
    val backend = RecordingBackend(
      List(
        ChatResponse(ChatMessage.AiMessage.from("Hello there")),
        ChatResponse(ChatMessage.AiMessage.from("I remember you said hello")),
      )
    )

    val result = for
      memory <- InMemoryChatMemory.create[IO, MemoryId]
      runtime = AiRuntime[IO](backend)
      ma = MemoryAwareRuntime(runtime, memory)
      first <- ma.chat(MemoryId("s-1"), Some("Be warm"), "hello")
      second <- ma.chat(MemoryId("s-1"), Some("Be warm"), "what do you remember?")
      stored <- memory.messages(MemoryId("s-1"))
    yield (first, second, stored)

    val (first, second, stored) = result.unsafeRunSync()

    assertEquals(first, "Hello there")
    assertEquals(second, "I remember you said hello")
    assertEquals(
      backend.requests(1).messages.map(_.text),
      List("Be warm", "hello", "Hello there", "what do you remember?"),
    )
    assertEquals(
      stored.map(_.text),
      List("hello", "Hello there", "what do you remember?", "I remember you said hello"),
    )
  }

  test("MemoryAwareRuntime does not persist the system prompt") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("ack"))))
    val program = for
      memory <- InMemoryChatMemory.create[IO, MemoryId]
      ma = MemoryAwareRuntime(AiRuntime[IO](backend), memory)
      _ <- ma.chat(MemoryId("s-1"), Some("be terse"), "hi")
      stored <- memory.messages(MemoryId("s-1"))
    yield stored

    val stored = program.unsafeRunSync()
    // Only user + assistant. System prompt is owned by the caller.
    assertEquals(stored.map(_.role), List("user", "assistant"))
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
            throw RuntimeException("No scripted responses remaining")
      }
