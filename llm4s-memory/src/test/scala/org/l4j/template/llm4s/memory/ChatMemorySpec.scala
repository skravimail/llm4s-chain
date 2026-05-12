package org.l4j.template.llm4s.memory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage

class ChatMemorySpec extends FunSuite:

  test("in-memory chat memory stores and appends messages per id") {
    val program = for
      memory <- InMemoryChatMemory.create[IO, MemoryId]
      _ <- memory.append(
        MemoryId("session-1"),
        List(
          ChatMessage.UserMessage.from("hello"),
          ChatMessage.AiMessage.from("hi"),
        ),
      )
      _ <- memory.append(
        MemoryId("session-2"),
        List(ChatMessage.UserMessage.from("separate")),
      )
      session1 <- memory.messages(MemoryId("session-1"))
      session2 <- memory.messages(MemoryId("session-2"))
    yield (session1, session2)

    val (session1, session2) = program.unsafeRunSync()

    assertEquals(session1.map(_.text), List("hello", "hi"))
    assertEquals(session2.map(_.text), List("separate"))
  }

  test("message window memory trims to the configured size") {
    val program = for
      base <- InMemoryChatMemory.create[IO, MemoryId]
      memory = MessageWindowMemory[IO, MemoryId](base, maxMessages = 3)
      _ <- memory.replace(
        MemoryId("window"),
        List(
          ChatMessage.UserMessage.from("1"),
          ChatMessage.AiMessage.from("2"),
          ChatMessage.UserMessage.from("3"),
          ChatMessage.AiMessage.from("4"),
        ),
      )
      messages <- memory.messages(MemoryId("window"))
    yield messages

    val messages = program.unsafeRunSync()

    assertEquals(messages.map(_.text), List("2", "3", "4"))
  }
