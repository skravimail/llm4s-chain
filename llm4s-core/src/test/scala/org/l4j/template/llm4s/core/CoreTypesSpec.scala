package org.l4j.template.llm4s.core

import munit.FunSuite

class CoreTypesSpec extends FunSuite:

  test("chat message text concatenates textual content and ignores images") {
    val message = ChatMessage.UserMessage(
      List(
        AiContent.Text("hello"),
        AiContent.Image("abc", "image/png"),
        AiContent.File("def", "application/pdf", Some("brief.pdf")),
        AiContent.Text(" world"),
      )
    )

    assertEquals(message.text, "hello world")
  }

  test("tool result message exposes textual view of multimodal tool output") {
    val result = ToolResult.Content(
      List(
        AiContent.Image("abc", "image/png"),
        AiContent.Text("caption"),
      )
    )
    val message = ChatMessage.ToolResultMessage("takePhoto", Some("call-1"), result)

    assertEquals(message.text, "caption")
  }

  test("model capabilities and usage expose convenience helpers") {
    val caps = ModelCapabilities(Set(ModelCapability.Streaming, ModelCapability.ToolCalling))
    val usage = Usage(inputTokens = 12, outputTokens = 7)

    assert(caps.supports(ModelCapability.Streaming))
    assert(!caps.supports(ModelCapability.Moderation))
    assertEquals(usage.totalTokens, 19)
  }

  test("chat request reports required capabilities for tools structured output and multimodal content") {
    val schema = JsonSchema.ObjectSchema(Map.empty)
    val request = ChatRequest(
      messages = List(
        ChatMessage.UserMessage(
          List(
            AiContent.Text("analyze"),
            AiContent.Image("abc", "image/png"),
            AiContent.File("def", "application/pdf"),
          )
        )
      ),
      tools = List(ToolSchema("lookup", "Lookup", schema)),
      responseFormat = Some(ResponseFormat.JsonSchema("Answer", schema)),
    )

    assertEquals(
      request.requiredCapabilities,
      Set(
        ModelCapability.VisionInput,
        ModelCapability.FileInput,
        ModelCapability.ToolCalling,
        ModelCapability.StructuredOutputJsonSchema,
      ),
    )
  }

  test("model capabilities validate request requirements") {
    val request = ChatRequest(
      messages = List(ChatMessage.UserMessage(List(AiContent.File("abc", "application/pdf"))))
    )
    val caps = ModelCapabilities(Set(ModelCapability.ToolCalling))

    val result = caps.validate(request)

    assert(result.left.exists(_.missing == Set(ModelCapability.FileInput)))
  }

  test("chat request appends tools and chat response reuses assistant text") {
    val schema = JsonSchema.ObjectSchema(
      properties = Map("city" -> JsonSchema.StringSchema(Some("City name"))),
      required = Set("city"),
    )
    val tool = ToolSchema("weather", "Fetch weather", schema)
    val request = ChatRequest(messages = List(ChatMessage.UserMessage.from("What is the weather?")))
      .withTool(tool)
    val response = ChatResponse(
      message = ChatMessage.AiMessage.from("Sunny"),
      finishReason = Some(FinishReason.Stop),
    )

    assertEquals(request.tools, List(tool))
    assertEquals(request.tools.head.parameters.propertyNames, Set("city"))
    assertEquals(response.text, "Sunny")
  }
