package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.dsl.*
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ToolDefinition
import org.l4j.template.llm4s.tools.ValueDecoder

/** Live Gemini LCEL demo on Gemini 2.5 Flash.
  *
  * This hard-wires the Gemini OpenAI-compatible endpoint and the
  * `gemini-2.5-flash` model, while still reusing the repo's standard
  * logging/tracing plumbing from `config.yaml`.
  *
  * Required env var:
  *
  *   GOOGLE_API_KEY=...
  *
  * Run with:
  *
  *   sbt "runMain org.l4j.template.demo.GeminiFlashTraceLcelDemoMain"
  */
final case class GeminiDefineArgs(term: String) derives SchemaEncoder, ValueDecoder

object GeminiFlashTraceLcelDemoMain extends IOApp.Simple:

  private val GeminiFlashModel = "gemini-2.5-flash"

  private val systemPrompt =
    "You are a concise Scala tutor. Always call the `define` tool first when the user asks about a term, " +
      "then respond in two short paragraphs."

  private object WikiLookup:
    def define(term: String): String =
      term.toLowerCase match
        case "monad"   => "A monad is a typed abstraction for sequencing effectful computations."
        case "functor" => "A functor supports structure-preserving mapping."
        case "fiber"   => "A fiber is a lightweight concurrent computation."
        case other     => s"No definition found for '$other'"

  private val defineTool: ToolDefinition[IO] =
    ToolDefinition.fromProduct[IO, GeminiDefineArgs](
      name = "define",
      description = "Look up a one-line definition for a programming term",
    ) { args =>
      IO.pure(ToolResult.Text(WikiLookup.define(args.term)))
    }

  private val chain =
    (Input.pick[IO, String, String]("question")(identity) >>
      PromptTemplate.user[IO, String](system = Some(systemPrompt))(identity)) |
      AiAgentRunnable.fromContext[IO](
        tools = defineTool.toToolKit,
      )

  private def resolvedAppConfig: AppConfig =
    val loaded = AppConfig.load()
    loaded.copy(
      llm = loaded.llm.copy(
        provider = Provider.Gemini,
        model = GeminiFlashModel,
        baseUrlOverride = None,
      ),
    )

  override def run: IO[Unit] =
    BackendSupport.fromAppConfig(resolvedAppConfig).use { case (backend, bundle, app) =>
      val ctx = RunContext[IO](
        backend0 = backend,
        runtimeConfig0 = RuntimeConfig(),
        runtimeListener0 = bundle.runtime,
      )

      for
        _ <- IO.println(s"--- Gemini LCEL trace demo against ${app.llm.provider} (${app.llm.model}) ---")
        _ <- IO.println(s"--- baseUrl=${app.llm.baseUrl} ---")
        _ <- IO.println(
          s"--- tracing: runtime=${app.tracing.runtime} http=${app.tracing.http} " +
            s"guardrails=${app.tracing.guardrails} workflow=${app.tracing.workflow} ---"
        )
        _ <- IO.println("")
        _ <- IO.println("Question: explain monad in Scala and why it matters.")
        _ <- IO.println("")
        answer <- chain.run("Explain monad in Scala and why it matters.", ctx)
        _ <- IO.println("")
        _ <- IO.println("Answer:")
        _ <- IO.println(answer)
        _ <- IO.println("")
        _ <- IO.println("Mermaid graph:")
        _ <- IO.println(chain.toMermaid)
      yield ()
    }
