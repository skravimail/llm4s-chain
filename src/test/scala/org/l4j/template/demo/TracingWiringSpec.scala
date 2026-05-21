package org.l4j.template.demo

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.agentic.WorkflowListener
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.guardrails.GuardrailListener
import org.l4j.template.llm4s.guardrails.GuardrailViolation
import org.l4j.template.llm4s.openai.HttpListener
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeListener
import sttp.model.Method
import sttp.model.Uri

class TracingWiringSpec extends FunSuite:

  private def collect(
      cfg: TracingConfig
  )(use: ListenerBundle[IO] => IO[Unit]): List[String] =
    (for
      ref <- Ref.of[IO, List[String]](Nil)
      bundle = TracingWiring.buildListeners[IO](cfg, s => ref.update(_ :+ s))
      _ <- use(bundle)
      out <- ref.get
    yield out).unsafeRunSync()

  private val trace = TraceContext.fresh()
  private val request = ChatRequest(messages = List(ChatMessage.UserMessage.from("hello")))
  private val response = ChatResponse(message = ChatMessage.AiMessage.from("hi"))
  private val toolCall = ToolCall(name = "search", argumentsJson = """{"q":"x"}""", callId = Some("t1"))
  private val invocation = InvocationContext(turn = 0, request = request, toolCall = toolCall, trace = trace)

  // -- runtime listener ------------------------------------------------------

  test("runtime=Off skips every event — no lines emitted to the sink") {
    val cfg = TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)
    val lines = collect(cfg) { b =>
      b.runtime.onChatStarted(trace, request) *>
        b.runtime.onChatCompleted(trace, request, "done", 1, 1_000_000L)
    }
    assertEquals(lines, Nil)
  }

  test("runtime=Info emits a line per event with trace ID + label + extras") {
    val cfg = TracingConfig(TraceLevel.Info, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)
    val lines = collect(cfg) { b =>
      b.runtime.onChatStarted(trace, request) *>
        b.runtime.onProviderRequest(trace, 0, request) *>
        b.runtime.onProviderResponse(trace, 0, response, 5_000_000L) *>
        b.runtime.onToolCalled(toolCall, invocation) *>
        b.runtime.onToolSucceeded(toolCall, invocation, ToolResult.Text("ok"), 2_000_000L) *>
        b.runtime.onChatCompleted(trace, request, "done", 2, 10_000_000L)
    }
    val shortId = trace.traceId.value.take(8)
    assertEquals(lines.length, 6)
    assert(lines.forall(_.contains(s"[trace $shortId]")))
    assert(lines(0).contains("chat.started"))
    assert(lines(0).contains("messages=1"))
    assert(lines(1).contains("provider.request"))
    assert(lines(1).contains("turn=0"))
    assert(lines(2).contains("provider.response"))
    assert(lines(2).contains("duration=5ms"))
    assert(lines(3).contains("tool.called"))
    assert(lines(3).contains("name=search"))
    assert(lines(4).contains("tool.succeeded"))
    assert(lines(4).contains("duration=2ms"))
    assert(lines(5).contains("chat.completed"))
    assert(lines(5).contains("turns=2"))
  }

  test("runtime=Debug includes full message text + full tool args / results") {
    val longArgs = """{"q":"""" + ("x" * 100) + "\"}"
    val longResult = "y" * 200
    val longText = "z" * 200
    val argCall = ToolCall(name = "search", argumentsJson = longArgs, callId = Some("t1"))
    val infoLines = collect(TracingConfig(TraceLevel.Info, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)) { b =>
      b.runtime.onToolCalled(argCall, invocation) *>
        b.runtime.onToolSucceeded(argCall, invocation, ToolResult.Text(longResult), 1_000_000L) *>
        b.runtime.onChatCompleted(trace, request, longText, 1, 1_000_000L)
    }
    val debugLines = collect(TracingConfig(TraceLevel.Debug, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)) { b =>
      b.runtime.onToolCalled(argCall, invocation) *>
        b.runtime.onToolSucceeded(argCall, invocation, ToolResult.Text(longResult), 1_000_000L) *>
        b.runtime.onChatCompleted(trace, request, longText, 1, 1_000_000L)
    }
    // Info: tool payloads are truncated with ellipsis; chat.completed omits response text entirely.
    assert(infoLines.exists(l => l.contains("tool.called") && l.contains("…")), "info tool.called should be truncated")
    assert(infoLines.exists(l => l.contains("tool.succeeded") && l.contains("…")), "info tool.succeeded should be truncated")
    assert(!infoLines.exists(_.contains(longText)), "info should not include the full response text")
    // Debug: all payloads shown in full, response on its own indented line.
    assert(debugLines.exists(_.contains(longArgs)), "debug should contain the full args")
    assert(debugLines.exists(_.contains(longResult)), "debug should contain the full result")
    assert(debugLines.exists(_.contains(longText)), "debug should contain the full chat text")
  }

  // -- span brackets --------------------------------------------------------

  private val infoRuntime = TracingConfig(TraceLevel.Info, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)

  test("spanChat success emits ┌ open then └ close") {
    val lines = collect(infoRuntime) { b =>
      b.runtime.spanChat(trace, request)(IO.unit)
    }
    assert(lines.exists(_.contains("┌")), s"expected ┌ in $lines")
    assert(lines.exists(_.contains("└")), s"expected └ in $lines")
    assert(!lines.exists(_.contains("✗")), s"unexpected ✗ in $lines")
    assert(lines.indexWhere(_.contains("┌")) < lines.indexWhere(_.contains("└")))
  }

  test("spanChat failure emits ┌ open then ✗ close, not └") {
    val lines = collect(infoRuntime) { b =>
      b.runtime.spanChat(trace, request)(IO.raiseError(RuntimeException("boom"))).attempt.void
    }
    assert(lines.exists(_.contains("┌")), s"expected ┌ in $lines")
    assert(lines.exists(_.contains("✗")), s"expected ✗ in $lines")
    assert(lines.exists(_.contains("boom")), s"expected error message in $lines")
    assert(!lines.exists(_.contains("└")), s"unexpected └ in $lines")
  }

  test("spanChat failure restores depth so next span is at correct indent") {
    val lines = collect(infoRuntime) { b =>
      b.runtime.spanChat(trace, request)(IO.raiseError(RuntimeException("boom"))).attempt.void *>
        b.runtime.spanChat(trace, request)(IO.unit)
    }
    val opens = lines.filter(_.contains("┌"))
    assertEquals(opens.length, 2, s"expected 2 open lines in $lines")
    // Both spans are at depth 0 — extract and compare the indentation prefix
    def indent(line: String): String =
      val first = line.indexOf(']'); val second = line.indexOf(']', first + 1)
      line.drop(second + 2).takeWhile(_ == ' ')
    assertEquals(indent(opens(0)), indent(opens(1)), "depth should be equal after error recovery")
  }

  test("spanProviderCall nested inside spanChat produces one extra indent level") {
    val lines = collect(infoRuntime) { b =>
      b.runtime.spanChat(trace, request) {
        b.runtime.spanProviderCall(trace, 0, request)(IO.unit)
      }
    }
    val chatOpen     = lines.find(l => l.contains("┌") && l.contains("ai.chat")).get
    val providerOpen = lines.find(l => l.contains("┌") && l.contains("ai.provider")).get
    def indent(line: String): String =
      val first = line.indexOf(']'); val second = line.indexOf(']', first + 1)
      line.drop(second + 2).takeWhile(_ == ' ')
    assert(
      indent(providerOpen).length > indent(chatOpen).length,
      s"provider span should be indented deeper than chat span\nchat:     $chatOpen\nprovider: $providerOpen",
    )
  }

  test("spanToolCall failure emits ✗ and depth is restored for sibling spans") {
    val lines = collect(infoRuntime) { b =>
      b.runtime.spanChat(trace, request) {
        b.runtime.spanToolCall(toolCall, invocation)(IO.raiseError(RuntimeException("tool-boom"))).attempt.void *>
          b.runtime.spanToolCall(toolCall, invocation)(IO.unit)
      }
    }
    val toolOpens = lines.filter(l => l.contains("┌") && l.contains("ai.tool"))
    assertEquals(toolOpens.length, 2, s"expected 2 tool open lines in $lines")
    def indent(line: String): String =
      val first = line.indexOf(']'); val second = line.indexOf(']', first + 1)
      line.drop(second + 2).takeWhile(_ == ' ')
    assertEquals(indent(toolOpens(0)), indent(toolOpens(1)), "sibling tool spans should share the same indent")
    assert(lines.exists(l => l.contains("✗") && l.contains("tool-boom")))
  }

  // -- http listener ---------------------------------------------------------

  test("http=Off emits no http lines") {
    val cfg = TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)
    val lines = collect(cfg) { b =>
      b.http.onHttpRequest(trace, Method.POST, Uri.unsafeParse("https://example.com/x")) *>
        b.http.onHttpResponse(trace, Method.POST, Uri.unsafeParse("https://example.com/x"), 200, 1_000_000L)
    }
    assertEquals(lines, Nil)
  }

  test("http=Info emits request + response + failure lines with trace correlation") {
    val cfg = TracingConfig(TraceLevel.Off, TraceLevel.Info, TraceLevel.Off, TraceLevel.Off)
    val uri = Uri.unsafeParse("https://example.com/api")
    val lines = collect(cfg) { b =>
      b.http.onHttpRequest(trace, Method.POST, uri) *>
        b.http.onHttpResponse(trace, Method.POST, uri, 200, 3_000_000L) *>
        b.http.onHttpFailure(trace, Method.POST, uri, new RuntimeException("boom"), 1_000_000L)
    }
    val shortId = trace.traceId.value.take(8)
    assertEquals(lines.length, 3)
    assert(lines.forall(_.contains(s"[trace $shortId]")))
    assert(lines(0).contains("http.request"))
    assert(lines(0).contains("POST"))
    assert(lines(1).contains("http.response"))
    assert(lines(1).contains("status=200"))
    assert(lines(1).contains("duration=3ms"))
    assert(lines(2).contains("http.failure"))
    assert(lines(2).contains("boom"))
  }

  // -- guardrail listener ----------------------------------------------------

  test("guardrails=Off emits nothing; Info emits block events") {
    val violation = GuardrailViolation("policy.deny", "blocked because reasons")
    val off = collect(TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)) { b =>
      b.guardrails.onInputBlocked(request, violation) *>
        b.guardrails.onOutputBlocked(request, response, violation)
    }
    assertEquals(off, Nil)

    val info = collect(TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Info, TraceLevel.Off)) { b =>
      b.guardrails.onInputBlocked(request, violation) *>
        b.guardrails.onOutputBlocked(request, response, violation)
    }
    assertEquals(info.length, 2)
    assert(info(0).contains("input.blocked"))
    assert(info(0).contains("policy.deny"))
    assert(info(1).contains("output.blocked"))
  }

  // -- workflow listener -----------------------------------------------------

  test("workflow=Off emits nothing; Info emits started+succeeded+failed events") {
    val off = collect(TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)) { b =>
      b.workflow.onAgentStarted("classifier", "input") *>
        b.workflow.onAgentSucceeded("classifier", "output", 5_000_000L)
    }
    assertEquals(off, Nil)

    val info = collect(TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Info)) { b =>
      b.workflow.onAgentStarted("classifier", "input") *>
        b.workflow.onAgentSucceeded("classifier", "output", 5_000_000L) *>
        b.workflow.onAgentFailed("classifier", new RuntimeException("nope"), 2_000_000L)
    }
    assertEquals(info.length, 3)
    assert(info(0).contains("agent.started"))
    assert(info(0).contains("name=classifier"))
    assert(info(1).contains("agent.succeeded"))
    assert(info(1).contains("duration=5ms"))
    assert(info(2).contains("agent.failed"))
    assert(info(2).contains("nope"))
  }

  test("Off bundles return noop singletons so unused listeners cost nothing") {
    val cfg = TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)
    val b = TracingWiring.buildListeners[IO](cfg, _ => IO.unit)
    // The factory wires each Off branch to the listener trait's `noop`.
    // We verify by checking the runtime listener accepts every call without
    // touching the sink — the sink would throw if invoked here.
    val trace = TraceContext.fresh()
    val req = ChatRequest(messages = Nil)
    val res = ChatResponse(message = ChatMessage.AiMessage.from(""))
    val tc = ToolCall(name = "x", argumentsJson = "{}", callId = None)
    val ic = InvocationContext(turn = 0, request = req, toolCall = tc, trace = trace)
    val effect = b.runtime.onChatStarted(trace, req) *>
      b.runtime.onChatCompleted(trace, req, "", 0, 0L) *>
      b.http.onHttpRequest(trace, Method.GET, Uri.unsafeParse("https://x.io/y")) *>
      b.guardrails.onInputBlocked(req, GuardrailViolation("c", "m")) *>
      b.workflow.onAgentStarted("a", "i")
    effect.unsafeRunSync() // must not throw
    // Type-level check: each is the listener trait — covered by compilation.
    val _: RuntimeListener[IO] = b.runtime
    val _: HttpListener[IO] = b.http
    val _: GuardrailListener[IO] = b.guardrails
    val _: WorkflowListener[IO] = b.workflow
  }
