package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.core.ToolSchema

class OpenAiCompatBackendSpec extends FunSuite:

  test("wire encoder includes model messages tools and json schema response format") {
    val schema = JsonSchema.ObjectSchema(
      properties = Map("city" -> JsonSchema.StringSchema(Some("City name"))),
      required = Set("city"),
    )
    val request = ChatRequest(
      messages = List(
        ChatMessage.SystemMessage.from("You are concise."),
        ChatMessage.UserMessage.from("Weather in Chicago?"),
      ),
      tools = List(ToolSchema("weather", "Fetch weather", schema)),
      responseFormat = Some(ResponseFormat.JsonSchema("WeatherAnswer", schema, strict = true)),
      temperature = Some(0.2),
      metadata = Map("tenant" -> "test"),
    )

    val json = OpenAiWire.encodeChatRequest("gpt-test", request)

    assertEquals(json("model").str, "gpt-test")
    assertEquals(json("messages")(0)("role").str, "system")
    assertEquals(json("messages")(1)("content").str, "Weather in Chicago?")
    assertEquals(json("tools")(0)("function")("name").str, "weather")
    assertEquals(json("response_format")("json_schema")("name").str, "WeatherAnswer")
    assertEquals(json("response_format")("json_schema")("strict").bool, true)
    assertEquals(json("temperature").num, 0.2)
    assertEquals(json("metadata")("tenant").str, "test")
  }

  test("wire encoder emits multimodal user content parts") {
    val request = ChatRequest(
      messages = List(
        ChatMessage.UserMessage(
          List(
            AiContent.Text("Inspect this."),
            AiContent.Image("image-bytes", "image/png", detail = Some("high")),
            AiContent.File("file-bytes", "application/pdf", fileName = Some("brief.pdf")),
          )
        )
      )
    )

    val json = OpenAiWire.encodeChatRequest("gpt-test", request)
    val content = json("messages")(0)("content").arr

    assertEquals(content(0)("type").str, "text")
    assertEquals(content(1)("type").str, "image_url")
    assertEquals(content(1)("image_url")("url").str, "data:image/png;base64,image-bytes")
    assertEquals(content(1)("image_url")("detail").str, "high")
    assertEquals(content(2)("type").str, "file")
    assertEquals(content(2)("file")("file_data").str, "data:application/pdf;base64,file-bytes")
    assertEquals(content(2)("file")("filename").str, "brief.pdf")
  }

  test("wire decoder maps assistant text tool calls usage and finish reason") {
    val raw = ujson.read(
      """{
        |  "id": "resp-1",
        |  "choices": [{
        |    "finish_reason": "tool_calls",
        |    "message": {
        |      "content": "I will call a tool.",
        |      "tool_calls": [{
        |        "id": "call-1",
        |        "function": {
        |          "name": "weather",
        |          "arguments": "{\"city\":\"Chicago\"}"
        |        }
        |      }]
        |    }
        |  }],
        |  "usage": {
        |    "prompt_tokens": 11,
        |    "completion_tokens": 5
        |  }
        |}""".stripMargin
    )

    val response = OpenAiWire.decodeChatResponse(raw)

    assertEquals(response.responseId, Some("resp-1"))
    assertEquals(response.text, "I will call a tool.")
    assertEquals(response.finishReason, Some(FinishReason.ToolCalls))
    assertEquals(response.message.toolCalls.map(_.name), List("weather"))
    assertEquals(response.message.toolCalls.head.callId, Some("call-1"))
    assertEquals(response.usage.map(_.totalTokens), Some(16))
  }

  test("backend delegates to transport with auth headers and decoded response") {
    final class RecordingTransport(response: ujson.Value) extends OpenAiTransport[IO]:
      var capturedPath: Option[String] = None
      var capturedBody: Option[ujson.Value] = None
      var capturedHeaders: Option[Map[String, String]] = None

      override def post(
          path: String,
          body: ujson.Value,
          headers: Map[String, String],
      ): IO[ujson.Value] =
        IO {
          capturedPath = Some(path)
          capturedBody = Some(body)
          capturedHeaders = Some(headers)
          response
        }

    val transport = RecordingTransport(
      ujson.read(
        """{
          |  "id": "resp-2",
          |  "choices": [{
          |    "finish_reason": "stop",
          |    "message": { "content": "Sunny" }
          |  }]
          |}""".stripMargin
      )
    )
    val backend = OpenAiCompatBackend[IO](
      OpenAiCompatConfig(
        baseUrl = "https://example.test/v1",
        apiKey = "secret",
        model = "gpt-test",
        defaultHeaders = Map("X-Test" -> "1"),
      ),
      transport,
    )

    val response = backend.chat(
      ChatRequest(messages = List(ChatMessage.UserMessage.from("Ping")))
    ).unsafeRunSync()

    assertEquals(transport.capturedPath, Some("/chat/completions"))
    assertEquals(transport.capturedHeaders.flatMap(_.get("Authorization")), Some("Bearer secret"))
    assertEquals(transport.capturedHeaders.flatMap(_.get("X-Test")), Some("1"))
    assertEquals(transport.capturedBody.map(_("model").str), Some("gpt-test"))
    assertEquals(response.text, "Sunny")
    assertEquals(response.finishReason, Some(FinishReason.Stop))
  }
