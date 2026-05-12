package org.l4j.template.llm4s.core

import munit.FunSuite

class CoreTypesSpec extends FunSuite:

  test("chat message text concatenates textual content and ignores images") {
    val message = ChatMessage.UserMessage(
      List(
        AiContent.Text("hello"),
        AiContent.Image("abc", "image/png"),
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
