package org.l4j.template.llm4s.guardrails

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.ToolExecutor
import org.l4j.template.llm4s.runtime.ToolKit

class GuardrailsSpec extends FunSuite:

  test("guarded backend blocks unsafe input before invoking the underlying backend") {
    val program = for
      calls <- Ref.of[IO, Int](0)
      backend = RecordingBackend(calls, ChatResponse(ChatMessage.AiMessage.from("safe")))
      guarded = GuardedChatBackend(
        backend,
        GuardrailChain(input = List(BlockWhenRequestContains("blocked"))),
      )
      error <- guarded.chat(ChatRequest(List(ChatMessage.UserMessage.from("blocked text")))).attempt
      count <- calls.get
    yield (error, count)

    val (result, count) = program.unsafeRunSync()

    assert(result.left.exists(_.isInstanceOf[GuardrailBlockedException]))
    assertEquals(count, 0)
  }

  test("guarded backend blocks unsafe output after invoking the underlying backend") {
    val program = for
      calls <- Ref.of[IO, Int](0)
      backend = RecordingBackend(calls, ChatResponse(ChatMessage.AiMessage.from("unsafe output")))
      guarded = GuardedChatBackend(
        backend,
        GuardrailChain(output = List(BlockWhenResponseContains("unsafe"))),
      )
      error <- guarded.chat(ChatRequest(List(ChatMessage.UserMessage.from("hello")))).attempt
      count <- calls.get
    yield (error, count)

    val (result, count) = program.unsafeRunSync()

    assert(result.left.exists(_.isInstanceOf[GuardrailBlockedException]))
    assertEquals(count, 1)
  }

  test("moderation input guardrail blocks flagged requests") {
    val guardrail = ModerationGuardrails.input(StaticModeration(flagged = true, Set("violence")))
    val result = guardrail.check(ChatRequest(List(ChatMessage.UserMessage.from("bad")))).unsafeRunSync()

    assert(result.isInstanceOf[GuardrailResult.Block])
  }

  test("guarded tool kit returns an error result when a tool call is blocked") {
    val base = ToolKit[IO](
      schemas = Nil,
      executors = Map(
        "lookup" -> new ToolExecutor[IO]:
          override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
            IO.pure(ToolResult.Text("should not run"))
      ),
    )
    val guarded = GuardedToolKit(
      base,
      GuardrailChain(tools = List(BlockTool("lookup"))),
    )

    val result = guarded.executors("lookup").execute(
      ToolCall("lookup", "{}"),
      InvocationContext(1, ChatRequest(Nil), ToolCall("lookup", "{}")),
    ).unsafeRunSync()

    assert(result.isError)
    assert(result.text.contains("tool call blocked by guardrail"))
  }

  test("retry policy retries recoverable backend failures") {
    val program = for
      remainingFailures <- Ref.of[IO, Int](1)
      backend = new ChatBackend[IO]:
        override def chat(request: ChatRequest): IO[ChatResponse] =
          remainingFailures.modify {
            case n if n > 0 => (n - 1, Left(RuntimeException("temporary")))
            case n          => (n, Right(ChatResponse(ChatMessage.AiMessage.from("ok"))))
          }.flatMap(IO.fromEither)
      guarded = GuardedChatBackend(
        backend,
        GuardrailChain.empty[IO],
        RetryPolicy.retryAll(maxAttempts = 2),
      )
      response <- guarded.chat(ChatRequest(List(ChatMessage.UserMessage.from("hello"))))
    yield response

    assertEquals(program.unsafeRunSync().text, "ok")
  }

private final case class RecordingBackend(
    calls: Ref[IO, Int],
    response: ChatResponse,
) extends ChatBackend[IO]:
  override def chat(request: ChatRequest): IO[ChatResponse] =
    calls.update(_ + 1).as(response)

private final case class BlockWhenRequestContains(token: String) extends InputGuardrail[IO]:
  override def check(request: ChatRequest): IO[GuardrailResult[ChatRequest]] =
    if request.messages.exists(_.text.contains(token)) then
      IO.pure(GuardrailResult.Block(GuardrailViolation("input.blocked", s"input contained $token")))
    else IO.pure(GuardrailResult.Allow(request))

private final case class BlockWhenResponseContains(token: String) extends OutputGuardrail[IO]:
  override def check(request: ChatRequest, response: ChatResponse): IO[GuardrailResult[ChatResponse]] =
    if response.text.contains(token) then
      IO.pure(GuardrailResult.Block(GuardrailViolation("output.blocked", s"output contained $token")))
    else IO.pure(GuardrailResult.Allow(response))

private final case class BlockTool(name: String) extends ToolGuardrail[IO]:
  override def check(call: ToolCall, context: InvocationContext): IO[GuardrailResult[ToolCall]] =
    if call.name == name then
      IO.pure(GuardrailResult.Block(GuardrailViolation("tool.blocked", s"tool ${call.name} blocked")))
    else IO.pure(GuardrailResult.Allow(call))

private final case class StaticModeration(
    flagged: Boolean,
    categories: Set[String],
) extends ModerationModel[IO]:
  override def moderate(text: String): IO[ModerationResult] =
    IO.pure(ModerationResult(flagged = flagged, categories = categories))

