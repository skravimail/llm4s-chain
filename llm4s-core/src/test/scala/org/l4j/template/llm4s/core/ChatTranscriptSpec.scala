package org.l4j.template.llm4s.core

import munit.FunSuite

class ChatTranscriptSpec extends FunSuite:

  private val sys1 = ChatMessage.SystemMessage.from("be terse")
  private val sys2 = ChatMessage.SystemMessage.from("always reply in JSON")
  private val u1 = ChatMessage.UserMessage.from("hi")
  private val a1 = ChatMessage.AiMessage.from("hello")
  private val u2 = ChatMessage.UserMessage.from("more")
  private val a2 = ChatMessage.AiMessage.from("ok")

  test("fromMessages: no system → system slot empty, turns preserved") {
    val t = ChatTranscript.fromMessages(List(u1, a1, u2, a2))
    assertEquals(t.system, None)
    assertEquals(t.turns, List(u1, a1, u2, a2))
  }

  test("fromMessages: leading single system → split correctly") {
    val t = ChatTranscript.fromMessages(List(sys1, u1, a1))
    assertEquals(t.system, Some(sys1))
    assertEquals(t.turns, List(u1, a1))
  }

  test("fromMessages: non-leading system is still extracted (no positional assumption)") {
    val t = ChatTranscript.fromMessages(List(u1, sys1, a1))
    assertEquals(t.system, Some(sys1))
    assertEquals(t.turns, List(u1, a1))
  }

  test("fromMessages: multiple system messages merge their text") {
    val t = ChatTranscript.fromMessages(List(sys1, u1, sys2, a1))
    assertEquals(t.system.map(_.text), Some("be terse\nalways reply in JSON"))
    assertEquals(t.turns, List(u1, a1))
  }

  test("toMessages: system goes first, turns in order") {
    val t = ChatTranscript(Some(sys1), List(u1, a1, u2))
    assertEquals(t.toMessages, List(sys1, u1, a1, u2))
  }

  test("toMessages: no system → just turns") {
    val t = ChatTranscript(None, List(u1, a1))
    assertEquals(t.toMessages, List(u1, a1))
  }

  test("withTurn appends non-system messages") {
    val t = ChatTranscript.empty.withSystem("s").withTurn(u1).withTurn(a1)
    assertEquals(t.system.map(_.text), Some("s"))
    assertEquals(t.turns, List(u1, a1))
  }

  test("withTurn rejects system messages") {
    intercept[IllegalArgumentException] {
      ChatTranscript.empty.withTurn(sys1)
    }
  }

  test("withoutSystem clears the system slot") {
    val t = ChatTranscript(Some(sys1), List(u1)).withoutSystem
    assertEquals(t.system, None)
    assertEquals(t.turns, List(u1))
  }
