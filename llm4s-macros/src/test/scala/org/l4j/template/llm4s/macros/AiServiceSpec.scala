package org.l4j.template.llm4s.macros

import cats.effect.IO
import munit.FunSuite
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.ToolDefinition
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder
import scala.annotation.experimental

@experimental
class AiServiceSpec extends FunSuite:

  test("materialized service executes plain prompt templates against the native runtime") {
    val backend = RecordingBackend(
      List(ChatResponse(ChatMessage.AiMessage.from("Hello, Ada.")))
    )

    val greeter = AiService.materialize[Greeter](backend)

    assertEquals(greeter.greet("Ada"), "Hello, Ada.")
    assertEquals(backend.requests.head.messages.map(_.text), List("Be friendly.", "Say hello to Ada"))
  }

  test("materialized service decodes structured json responses") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage.from("""{"summary":"Strong Scala fit","score":9}""")
        )
      )
    )

    val reviewer = AiService.materialize[Reviewer](backend)

    assertEquals(reviewer.review("Scala engineer"), Review("Strong Scala fit", 9))
    assertEquals(
      backend.requests.head.responseFormat,
      Some(org.l4j.template.llm4s.core.ResponseFormat.JsonSchema("Review", summon[StructuredCodec[Review]].schema, strict = true)),
    )
  }

  test("materialized service continues through native tool calls") {
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
        ChatResponse(ChatMessage.AiMessage.from("Scala is a functional-first JVM language.")),
      )
    )

    val defineTool = ToolDefinition.fromProduct[IO, DefineArgs]("define", "Define a term") { args =>
      IO.pure(ToolResult.Text(s"${args.term}:definition"))
    }

    val tutor = AiService.materialize[Tutor](backend, defineTool.toToolKit)

    assertEquals(tutor.explain("Scala"), "Scala is a functional-first JVM language.")
    assertEquals(backend.requests.size, 2)
    assertEquals(backend.requests.head.tools.map(_.name), List("define"))
    assertEquals(backend.requests(1).messages.last.text, "Scala:definition")
  }

  test("invalid prompt placeholders fail at compile time") {
    val errors = compileErrors(
      """import scala.annotation.experimental
import org.l4j.template.llm4s.macros.*

trait Broken:
  @user("Hello {{missing}}")
  def greet(name: String): String

val backend = new org.l4j.template.llm4s.core.ChatBackend[cats.effect.IO]:
  override def chat(request: org.l4j.template.llm4s.core.ChatRequest) =
    cats.effect.IO.pure(org.l4j.template.llm4s.core.ChatResponse(
      org.l4j.template.llm4s.core.ChatMessage.AiMessage.from("nope")
    ))

@experimental object Scope:
  val service = AiService.materialize[Broken](backend)
"""
    )

    assert(errors.contains("template placeholder {{missing}} does not match any parameter"))
  }

  trait Greeter:
    @system("Be friendly.")
    @user("Say hello to {{name}}")
    def greet(name: String): String

  trait Reviewer:
    @user("Review this candidate: {{candidate}}")
    def review(candidate: String): Review

  trait Tutor:
    @user("Explain {{term}}")
    def explain(term: String): String

  final case class Review(summary: String, score: Int)
      derives StructuredCodec,
        SchemaEncoder,
        ValueDecoder,
        upickle.default.ReadWriter

  final case class DefineArgs(term: String)
      derives upickle.default.ReadWriter,
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
