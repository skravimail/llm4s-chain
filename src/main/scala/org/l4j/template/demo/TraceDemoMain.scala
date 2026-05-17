package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.Resource
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.openai.HttpListener
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig
import org.l4j.template.llm4s.openai.SttpOpenAiTransport
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.runtime.ToolErrorPolicy
import org.l4j.template.llm4s.runtime.ToolKit
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Method
import sttp.model.Uri

/** Demonstrates the PR-8 / PR-8b..PR-8f tracing seams end-to-end.
  *
  * Wires a `RuntimeListener` (chat / provider / tool events) AND an
  * `HttpListener` (HTTP wire events) and prints each one to stdout as it
  * fires. Run after `set -a; source .env; set +a`:
  *
  *   sbt "runMain org.l4j.template.demo.TraceDemoMain"
  *
  * The output proves a single `TraceContext.traceId` flows through every
  * layer for one chat invocation — chat start, per-turn provider request,
  * sttp HTTP request, sttp HTTP response, tool invocation, the next
  * provider turn, and chat completion all share the same id.
  */
object TraceDemoMain extends IOApp.Simple:

  // -- Listener that pretty-prints each event with its trace ID ------------

  private def shortId(t: TraceContext): String =
    t.traceId.value.take(8)

  private def stamp(label: String, trace: TraceContext, extra: String = ""): IO[Unit] =
    val line = f"[trace ${shortId(trace)}] $label%-22s $extra"
    IO.println(line)

  private val runtimeListener: RuntimeListener[IO] = new RuntimeListener.Default[IO]:
    override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
      stamp("chat.started", t, s"messages=${r.messages.length} tools=${r.tools.length}")
    override def onChatCompleted(t: TraceContext, r: ChatRequest, text: String, turns: Int, d: Long): IO[Unit] =
      stamp("chat.completed", t, f"turns=$turns duration=${d / 1_000_000}%dms text=${text.take(40)}...")
    override def onChatFailed(t: TraceContext, r: ChatRequest, e: Throwable): IO[Unit] =
      stamp("chat.failed", t, s"error=${e.getClass.getSimpleName}: ${e.getMessage}")
    override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): IO[Unit] =
      stamp("provider.request", t, s"turn=$turn messages=${r.messages.length}")
    override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): IO[Unit] =
      stamp("provider.response", t, f"turn=$turn duration=${d / 1_000_000}%dms toolCalls=${r.message.toolCalls.length}")
    override def onToolCalled(c: ToolCall, ctx: InvocationContext): IO[Unit] =
      stamp("tool.called", ctx.trace, s"name=${c.name} args=${c.argumentsJson.take(40)}")
    override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, result: ToolResult, d: Long): IO[Unit] =
      stamp("tool.succeeded", ctx.trace, f"name=${c.name} duration=${d / 1_000_000}%dms result=${result.text.take(40)}")
    override def onToolFailed(c: ToolCall, ctx: InvocationContext, e: Throwable, attempt: Int, willRetry: Boolean): IO[Unit] =
      stamp("tool.failed", ctx.trace, s"name=${c.name} attempt=$attempt willRetry=$willRetry msg=${e.getMessage}")

  private val httpListener: HttpListener[IO] = new HttpListener.Default[IO]:
    override def onHttpRequest(t: TraceContext, m: Method, u: Uri): IO[Unit] =
      stamp("http.request", t, s"${m.method} ${u}")
    override def onHttpResponse(t: TraceContext, m: Method, u: Uri, s: Int, d: Long): IO[Unit] =
      stamp("http.response", t, f"status=$s duration=${d / 1_000_000}%dms")
    override def onHttpFailure(t: TraceContext, m: Method, u: Uri, e: Throwable, d: Long): IO[Unit] =
      stamp("http.failure", t, f"error=${e.getClass.getSimpleName}: ${e.getMessage} duration=${d / 1_000_000}%dms")

  // -- Backend wired with both listeners -----------------------------------

  private def backendResource(app: AppConfig): Resource[IO, ChatBackend[IO]] =
    AsyncHttpClientCatsBackend
      .resourceUsingConfigBuilder[IO](updateConfig = _
        .setRequestTimeout(app.llm.requestTimeout.toMillis.toInt)
        .setReadTimeout(app.llm.requestTimeout.toMillis.toInt))
      .map { sttpBackend =>
        val transport = SttpOpenAiTransport[IO](
          sttp.model.Uri.unsafeParse(app.llm.baseUrl),
          sttpBackend,
          httpListener,
        )
        OpenAiCompatBackend[IO](
          OpenAiCompatConfig(
            baseUrl = app.llm.baseUrl,
            apiKey = app.llm.apiKey,
            model = app.llm.model,
            responseFormatMode = app.llm.responseFormatMode,
            requestTimeout = app.llm.requestTimeout,
          ),
          transport,
        )
      }

  // -- A trivial tool so we exercise the tool-call leg too -----------------

  private val defineTool: ToolKit[IO] = ToolKit[IO](
    schemas = List(
      org.l4j.template.llm4s.core.ToolSchema(
        name = "define",
        description = "Look up a one-line definition for a programming term.",
        parameters = org.l4j.template.llm4s.core.JsonSchema.ObjectSchema(
          properties = Map(
            "term" -> org.l4j.template.llm4s.core.JsonSchema.StringSchema(Some("Term to define."))
          ),
          required = Set("term"),
        ),
      )
    ),
    executors = Map(
      "define" -> new org.l4j.template.llm4s.runtime.ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          // Trivial in-process implementation — the point is the trace.
          IO.pure(ToolResult.Text("A monad is a design pattern for sequencing effectful computations."))
    ),
  )

  override def run: IO[Unit] =
    val app = AppConfig.load()
    backendResource(app).use { backend =>
      val runtime = AiRuntime[IO](
        backend,
        RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.SurfaceToModel),
        runtimeListener,
      )

      for
        _ <- IO.println(s"--- trace demo against ${app.llm.provider} (${app.llm.model}) ---")
        _ <- IO.println("")
        _ <- runtime.chat(
          system = Some(
            "You are a programming tutor. When the user asks about a term, " +
              "call the `define` tool first to get a precise definition, then " +
              "expand on it in one or two sentences."
          ),
          userText = "Explain: monad",
          toolKit = defineTool,
        ).void
        _ <- IO.println("")
        _ <- IO.println("--- end ---")
      yield ()
    }
