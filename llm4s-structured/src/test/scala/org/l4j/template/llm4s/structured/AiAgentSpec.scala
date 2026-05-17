package org.l4j.template.llm4s.structured

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.runtime.ToolExecutor
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

class AiAgentSpec extends FunSuite:

  test("AiAgent.chat returns the assistant's plain text") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("Hello"))))
    val agent   = AiAgent[IO](backend)

    val result = agent.chat("Be terse", "Hi").unsafeRunSync()

    assertEquals(result, "Hello")
    assertEquals(backend.requests.length, 1)
    assertEquals(backend.requests.head.messages.map(_.role), List("system", "user"))
    assertEquals(backend.requests.head.tools, Nil)
    assertEquals(backend.requests.head.responseFormat, None)
  }

  test("AiAgent.chatAs decodes a structured response and requests a JSON schema") {
    val backend = RecordingBackend(
      List(ChatResponse(ChatMessage.AiMessage.from("""{"summary":"Good","score":9}""")))
    )
    val agent = AiAgent[IO](backend)

    val result = agent.chatAs[Review]("Return JSON.", "Review Scala").unsafeRunSync()

    assertEquals(result, Review("Good", 9))
    assertEquals(
      backend.requests.head.responseFormat,
      Some(ResponseFormat.JsonSchema("Review", summon[StructuredCodec[Review]].schema, strict = true)),
    )
  }

  test("AiAgent.withTools attaches a ToolKit without mutating the original agent") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = List(AiContent.Text("")),
            toolCalls = List(ToolCall("ping", "{}", Some("call-1"))),
            finishReason = Some(FinishReason.ToolCalls),
          ),
          finishReason = Some(FinishReason.ToolCalls),
        ),
        ChatResponse(ChatMessage.AiMessage.from("pong")),
      )
    )
    val schema = ToolSchema(
      name = "ping",
      description = "ping back",
      parameters = JsonSchema.ObjectSchema(Map.empty),
    )
    val kit = ToolKit[IO](
      schemas = List(schema),
      executors = Map(
        "ping" -> new ToolExecutor[IO]:
          override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
            IO.pure(ToolResult.Text("ok"))
      ),
    )

    val base   = AiAgent[IO](backend)
    val tooled = base.withTools(kit)

    assertEquals(base.tools.schemas, Nil)
    assertEquals(tooled.tools.schemas.map(_.name), List("ping"))

    val result = tooled.chat("Use ping", "ping please").unsafeRunSync()

    assertEquals(result, "pong")
    assertEquals(backend.requests.length, 2)
    assertEquals(backend.requests.head.tools.map(_.name), List("ping"))
  }

  test("AiAgent.withTools reuses the same underlying AiRuntime") {
    val backend = RecordingBackend(Nil)
    val base = AiAgent[IO](backend)
    val withA = base.withTools(ToolKit.empty[IO])
    val withB = withA.withTools(ToolKit.empty[IO])

    // Reference equality: not just structurally equal — actually the same
    // instance, so any state the runtime acquires is shared across derived
    // agents.
    assert(base.runtime eq withA.runtime)
    assert(withA.runtime eq withB.runtime)
  }

  test("AiAgent.withConfig builds a new runtime (config is baked in)") {
    val backend = RecordingBackend(Nil)
    val base = AiAgent[IO](backend)
    val tweaked = base.withConfig(RuntimeConfig(maxTurns = 3))

    assert(!(base.runtime eq tweaked.runtime))
    assertEquals(tweaked.config.maxTurns, 3)
  }

  test("AiAgent.chat(request, opts) overrides toolKit, temperature, and responseFormat per-call") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("ok"))))
    val baseSchema = ToolSchema("base", "b", JsonSchema.ObjectSchema(Map.empty))
    val baseKit = ToolKit[IO](
      List(baseSchema),
      Map("base" -> new ToolExecutor[IO]:
        override def execute(c: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          IO.pure(ToolResult.Text(""))),
    )
    val agent = AiAgent[IO](backend, baseKit)

    val perCallSchema = ToolSchema("override", "o", JsonSchema.ObjectSchema(Map.empty))
    val perCallKit = ToolKit[IO](
      List(perCallSchema),
      Map("override" -> new ToolExecutor[IO]:
        override def execute(c: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          IO.pure(ToolResult.Text(""))),
    )

    val opts = ChatOptions[IO](
      toolKit = Some(perCallKit),
      temperature = Some(0.1),
      metadata = Map("trace" -> "t1"),
    )

    val result = agent
      .chat(
        ChatRequest(messages = List(ChatMessage.UserMessage.from("hi"))),
        opts,
      )
      .unsafeRunSync()

    assertEquals(result, "ok")
    val sent = backend.requests.head
    assertEquals(sent.tools.map(_.name), List("override"))
    assertEquals(sent.temperature, Some(0.1))
    assertEquals(sent.metadata.get("trace"), Some("t1"))
  }

  test("AiAgent.chat(messages, opts) accepts a multi-turn message list") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("merged"))))
    val agent = AiAgent[IO](backend)

    val result = agent
      .chat(
        List(
          ChatMessage.UserMessage.from("turn 1"),
          ChatMessage.AiMessage.from("reply 1"),
          ChatMessage.UserMessage.from("turn 2"),
        )
      )
      .unsafeRunSync()

    assertEquals(result, "merged")
    assertEquals(backend.requests.head.messages.map(_.role), List("user", "assistant", "user"))
  }

  test("AiAgent.chatAs fires the wired RuntimeListener (regression: chatAs used to bypass it)") {
    val backend = RecordingBackend(
      List(ChatResponse(ChatMessage.AiMessage.from("""{"summary":"Good","score":9}""")))
    )
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val listener = new RuntimeListener.Default[IO]:
      override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
        IO { seen += "chat.started"; () }
      override def onChatCompleted(t: TraceContext, r: ChatRequest, text: String, turns: Int, d: Long): IO[Unit] =
        IO { seen += "chat.completed"; () }
    val agent = AiAgent[IO](backend, ToolKit.empty[IO], RuntimeConfig(), listener)

    val result = agent.chatAs[Review]("Return JSON.", "Review Scala").unsafeRunSync()

    assertEquals(result, Review("Good", 9))
    assertEquals(seen.toList, List("chat.started", "chat.completed"))
  }

  test("AiAgent(backend, tools, config, listener) wires the listener into the runtime") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("hi"))))
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val listener = new RuntimeListener.Default[IO]:
      override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
        IO { seen += "chat.started"; () }
      override def onChatCompleted(t: TraceContext, r: ChatRequest, text: String, turns: Int, d: Long): IO[Unit] =
        IO { seen += s"chat.completed:$text"; () }
    val agent = AiAgent[IO](backend, ToolKit.empty[IO], RuntimeConfig(), listener)

    val result = agent.chat("be terse", "hello").unsafeRunSync()

    assertEquals(result, "hi")
    assertEquals(seen.toList, List("chat.started", "chat.completed:hi"))
  }

  test("AiAgent.withConfig overrides runtime config without mutating the original") {
    val infiniteToolCall = ChatResponse(
      ChatMessage.AiMessage(
        contents = Nil,
        toolCalls = List(ToolCall("loop", "{}", Some("call-1"))),
        finishReason = Some(FinishReason.ToolCalls),
      ),
      finishReason = Some(FinishReason.ToolCalls),
    )
    val backend = RecordingBackend(List(infiniteToolCall, infiniteToolCall))
    val kit = ToolKit[IO](
      schemas = List(ToolSchema("loop", "loops", JsonSchema.ObjectSchema(Map.empty))),
      executors = Map(
        "loop" -> new ToolExecutor[IO]:
          override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
            IO.pure(ToolResult.Text("again"))
      ),
    )

    val base    = AiAgent[IO](backend, kit)
    val limited = base.withConfig(RuntimeConfig(maxTurns = 1))

    assertEquals(base.config.maxTurns, RuntimeConfig().maxTurns)
    assertEquals(limited.config.maxTurns, 1)

    val error = intercept[org.l4j.template.llm4s.runtime.AiRuntimeError.MaxTurnsExceeded] {
      limited.chat("loop", "go").unsafeRunSync()
    }
    assertEquals(error.maxTurns, 1)
  }

  private final case class Review(summary: String, score: Int)
      derives StructuredCodec,
        SchemaEncoder,
        ValueDecoder

  private final case class RecordingBackend(
      scriptedResponses: List[ChatResponse]
  ) extends ChatBackend[IO]:
    private var remaining = scriptedResponses
    private var seen = Vector.empty[ChatRequest]

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
