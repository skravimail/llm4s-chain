package org.l4j.template.llm4s.runtime

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
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.memory.InMemoryChatMemory
import org.l4j.template.llm4s.memory.MemoryId

class AiRuntimeSpec extends FunSuite:

  test("runtime returns plain assistant text when no tools are requested") {
    val backend = RecordingBackend(List(ChatResponse(ChatMessage.AiMessage.from("Hello there"))))
    val runtime = AiRuntime[IO](backend)

    val result = runtime.chat(Some("Be terse"), "Hi").unsafeRunSync()

    assertEquals(result, "Hello there")
    assertEquals(backend.requests.length, 1)
    assertEquals(backend.requests.head.messages.map(_.role), List("system", "user"))
    assertEquals(backend.requests.head.tools, Nil)
  }

  test("runtime executes tool calls and continues the conversation") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = List(AiContent.Text("")),
            toolCalls = List(ToolCall("define", """{"term":"Scala"}""", Some("call-1"))),
            finishReason = Some(FinishReason.ToolCalls),
          ),
          finishReason = Some(FinishReason.ToolCalls),
        ),
        ChatResponse(ChatMessage.AiMessage.from("Scala is a JVM language.")),
      )
    )
    val toolSchema = ToolSchema(
      name = "define",
      description = "Define a term",
      parameters = JsonSchema.ObjectSchema(
        properties = Map("term" -> JsonSchema.StringSchema(Some("Term to define"))),
        required = Set("term"),
      ),
    )
    val toolkit = ToolKit[IO](
      schemas = List(toolSchema),
      executors = Map(
        "define" -> new ToolExecutor[IO]:
          override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
            IO.pure(ToolResult.Text(s"Defined ${call.argumentsJson} on turn ${context.turn}"))
      ),
    )
    val runtime = AiRuntime[IO](backend)

    val result = runtime.chat(None, "What is Scala?", toolkit).unsafeRunSync()

    assertEquals(result, "Scala is a JVM language.")
    assertEquals(backend.requests.length, 2)
    assertEquals(backend.requests.head.tools.map(_.name), List("define"))
    assertEquals(backend.requests(1).messages.map(_.role), List("user", "assistant", "tool"))
    assertEquals(
      backend.requests(1).messages.last.text,
      """Defined {"term":"Scala"} on turn 1""",
    )
  }

  test("runtime fails after the configured max tool turns") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = Nil,
            toolCalls = List(ToolCall("loop", "{}", Some("call-1"))),
            finishReason = Some(FinishReason.ToolCalls),
          )
        )
      )
    )
    val toolkit = ToolKit[IO](
      schemas = List(
        ToolSchema(
          "loop",
          "Loop forever",
          JsonSchema.ObjectSchema(Map.empty),
        )
      ),
      executors = Map(
        "loop" -> new ToolExecutor[IO]:
          override def execute(call: ToolCall, context: InvocationContext): IO[ToolResult] =
            IO.pure(ToolResult.Text("again"))
      ),
    )
    val runtime = AiRuntime[IO](backend, RuntimeConfig(maxTurns = 1))

    val error = intercept[RuntimeException] {
      runtime.chat(None, "loop", toolkit).unsafeRunSync()
    }

    assertEquals(error.getMessage, "AiRuntime chat exceeded 1 tool-call turns")
  }

  test("runtime persists conversational history through chat memory") {
    val backend = RecordingBackend(
      List(
        ChatResponse(ChatMessage.AiMessage.from("Hello there")),
        ChatResponse(ChatMessage.AiMessage.from("I remember you said hello")),
      )
    )

    val result = for
      memory <- InMemoryChatMemory.create[IO, MemoryId]
      runtime = AiRuntime[IO](backend)
      first <- runtime.chatWithMemory(memory, MemoryId("s-1"), Some("Be warm"), "hello")
      second <- runtime.chatWithMemory(memory, MemoryId("s-1"), Some("Be warm"), "what do you remember?")
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
