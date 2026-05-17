package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolErrorPolicy
import org.l4j.template.llm4s.runtime.ToolKit

/** Demonstrates the PR-8 / PR-8b..PR-8f tracing seams end-to-end.
  *
  * Listener wiring is driven entirely by `config.yaml` (PR-23) via
  * [[BackendSupport.fromConfig]] — same plumbing every other demo uses,
  * so the only thing special here is the deliberately tool-using prompt
  * that exercises every event type in a single run.
  *
  *   tracing:
  *     runtime: info       # off | info | debug
  *     http: info          # off | info | debug
  *     guardrails: off
  *     workflow: off
  *
  * Run after `set -a; source .env; set +a`:
  *
  *   sbt "runMain org.l4j.template.demo.TraceDemoMain"
  *
  * The output proves a single `TraceContext.traceId` flows through every
  * layer for one chat invocation — chat start, per-turn provider request,
  * sttp HTTP request, sttp HTTP response, tool invocation, the next
  * provider turn, and chat completion all share the same id. */
object TraceDemoMain extends IOApp.Simple:

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
          IO.pure(ToolResult.Text("A monad is a design pattern for sequencing effectful computations."))
    ),
  )

  override def run: IO[Unit] =
    BackendSupport.fromConfig.use { case (backend, bundle, app) =>
      val runtime = AiRuntime[IO](
        backend,
        RuntimeConfig(toolFailurePolicy = ToolErrorPolicy.SurfaceToModel),
        bundle.runtime,
      )

      for
        _ <- IO.println(s"--- trace demo against ${app.llm.provider} (${app.llm.model}) ---")
        _ <- IO.println(
          s"--- tracing: runtime=${app.tracing.runtime} http=${app.tracing.http} " +
            s"guardrails=${app.tracing.guardrails} workflow=${app.tracing.workflow} ---"
        )
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
