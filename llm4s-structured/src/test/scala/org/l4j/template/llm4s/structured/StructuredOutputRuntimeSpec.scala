package org.l4j.template.llm4s.structured

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

class StructuredOutputRuntimeSpec extends FunSuite:

  test("structured codec derives schema and decodes values") {
    val codec = summon[StructuredCodec[Review]]

    assertEquals(codec.schemaName, "Review")
    assertEquals(
      codec.decode("""{"summary":"Good fit","score":8}"""),
      Right(Review("Good fit", 8)),
    )
  }

  test("structured runtime requests json schema output and decodes typed results") {
    val backend = RecordingBackend(
      List(ChatResponse(ChatMessage.AiMessage.from("""{"summary":"Native","score":10}""")))
    )

    val result = StructuredOutputRuntime.chat[IO, Review](
      backend = backend,
      config = org.l4j.template.llm4s.runtime.RuntimeConfig(),
      system = Some("Return JSON only."),
      userText = "Review Scala",
      toolKit = ToolKit.empty[IO],
    ).unsafeRunSync()

    assertEquals(result, Review("Native", 10))
    assertEquals(
      backend.requests.head.responseFormat,
      Some(ResponseFormat.JsonSchema("Review", summon[StructuredCodec[Review]].schema, strict = true)),
    )
  }

  final case class Review(summary: String, score: Int)
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
