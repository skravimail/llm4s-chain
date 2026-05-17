package org.l4j.template.llm4s.guardrails

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.InvocationContext
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolSchema

class GuardrailListenerSpec extends FunSuite:

  sealed trait Event
  object Event:
    final case class InputBlocked(code: String) extends Event
    final case class OutputBlocked(code: String) extends Event
    final case class ToolBlocked(name: String, code: String) extends Event

  private def recordingListener(buf: Ref[IO, Vector[Event]]): GuardrailListener[IO] =
    new GuardrailListener.Default[IO]:
      override def onInputBlocked(r: ChatRequest, v: GuardrailViolation): IO[Unit] =
        buf.update(_ :+ Event.InputBlocked(v.code))
      override def onOutputBlocked(r: ChatRequest, p: ChatResponse, v: GuardrailViolation): IO[Unit] =
        buf.update(_ :+ Event.OutputBlocked(v.code))
      override def onToolBlocked(c: ToolCall, ctx: InvocationContext, v: GuardrailViolation): IO[Unit] =
        buf.update(_ :+ Event.ToolBlocked(c.name, v.code))

  private val blockInput: InputGuardrail[IO] = (_: ChatRequest) =>
    IO.pure(GuardrailResult.Block(GuardrailViolation("in.deny", "no")))

  private val blockOutput: OutputGuardrail[IO] =
    (_: ChatRequest, _: ChatResponse) =>
      IO.pure(GuardrailResult.Block(GuardrailViolation("out.deny", "no")))

  private val blockTool: ToolGuardrail[IO] =
    (_: ToolCall, _: InvocationContext) =>
      IO.pure(GuardrailResult.Block(GuardrailViolation("tool.deny", "no")))

  private val sampleRequest =
    ChatRequest(List(ChatMessage.UserMessage.from("hi")))
  private val sampleResponse = ChatResponse(ChatMessage.AiMessage.from("ok"))
  private val sampleCall = ToolCall("lookup", "{}")
  private val sampleCtx = InvocationContext(
    turn = 0,
    request = sampleRequest,
    toolCall = sampleCall,
  )

  test("checkInput fires onInputBlocked before raising") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      chain = GuardrailChain[IO](input = List(blockInput)).withListener(recordingListener(buf))
      _ <- chain.checkInput(sampleRequest).attempt
      events <- buf.get
    yield events

    assertEquals(program.unsafeRunSync(), Vector(Event.InputBlocked("in.deny")))
  }

  test("checkOutput fires onOutputBlocked before raising") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      chain = GuardrailChain[IO](output = List(blockOutput)).withListener(recordingListener(buf))
      _ <- chain.checkOutput(sampleRequest, sampleResponse).attempt
      events <- buf.get
    yield events

    assertEquals(program.unsafeRunSync(), Vector(Event.OutputBlocked("out.deny")))
  }

  test("checkTool fires onToolBlocked before raising") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      chain = GuardrailChain[IO](tools = List(blockTool)).withListener(recordingListener(buf))
      _ <- chain.checkTool(sampleCall, sampleCtx).attempt
      events <- buf.get
    yield events

    assertEquals(program.unsafeRunSync(), Vector(Event.ToolBlocked("lookup", "tool.deny")))
  }

  test("noop listener (default) does not fire any events") {
    val program = for
      chain <- IO.pure(GuardrailChain[IO](input = List(blockInput)))
      // No .withListener — listener is None; we should still get the typed exception
      err <- chain.checkInput(sampleRequest).attempt
    yield err

    val result = program.unsafeRunSync()
    assert(result.left.exists(_.isInstanceOf[GuardrailBlockedException]))
  }

  // Suppress unused-import warnings for symbols only used in test scope.
  @annotation.unused private val _unused = (JsonSchema.ObjectSchema(Map.empty), ToolSchema("x", "x", JsonSchema.ObjectSchema(Map.empty)))
