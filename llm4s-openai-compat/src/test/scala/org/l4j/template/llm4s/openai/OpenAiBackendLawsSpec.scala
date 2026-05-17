package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackendLaws
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolSchema

/** Provider-neutral conformance suite. Any new ChatBackend implementation can
  * follow this template to prove it observes the documented invariants. */
class OpenAiBackendLawsSpec extends FunSuite:

  test("message ordering and tool schemas round-trip through the OpenAI wire") {
    val captureTransport = new CapturingTransport(
      ujson.read(
        """{"id":"r","choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
      )
    )
    val backend = new OpenAiCompatBackend[IO](
      OpenAiCompatConfig("https://example.test/v1", "k", "m"),
      captureTransport,
    )

    val request = ChatRequest(
      messages = List(
        ChatMessage.SystemMessage.from("s"),
        ChatMessage.UserMessage.from("u1"),
        ChatMessage.UserMessage.from("u2"),
      ),
      tools = List(
        ToolSchema("alpha", "first", JsonSchema.ObjectSchema(Map.empty)),
        ToolSchema("beta", "second", JsonSchema.ObjectSchema(Map.empty)),
      ),
    )

    val response = backend.chat(request).unsafeRunSync()

    val observedRoles = captureTransport.lastBody
      .flatMap(_.obj.get("messages"))
      .map(_.arr.toList.map(_("role").str))
      .getOrElse(Nil)
    val observedTools = captureTransport.lastBody
      .flatMap(_.obj.get("tools"))
      .map(_.arr.toList.map(_("function")("name").str))
      .getOrElse(Nil)

    val failures =
      ChatBackendLaws.checkMessageOrderPreserved(request, observedRoles) ++
        ChatBackendLaws.checkToolsRoundTrip(request, observedTools) ++
        ChatBackendLaws.checkResponseInvariants(response)

    ChatBackendLaws.report(failures).foreach(fail(_))
  }

  test("response invariants hold on a synthetic content_filter response") {
    val resp = OpenAiWire.decodeChatResponse(
      ujson.read(
        """{"id":"r","choices":[{"finish_reason":"content_filter","message":{"content":"redacted"}}]}"""
      )
    )
    ChatBackendLaws.report(ChatBackendLaws.checkResponseInvariants(resp)).foreach(fail(_))
  }

  private final class CapturingTransport(stub: ujson.Value) extends OpenAiTransport[IO]:
    @volatile var lastBody: Option[ujson.Value] = None
    override def post(
        path: String,
        body: ujson.Value,
        headers: Map[String, String],
    ): IO[ujson.Value] =
      IO {
        lastBody = Some(body)
        stub
      }
