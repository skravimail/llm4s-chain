package org.l4j.template.l4j_macro.demo.agentic

import dev.langchain4j.agentic.{ AgenticServices, UntypedAgent }
import dev.langchain4j.model.openai.OpenAiChatModel
import org.l4j.template.l4j_macro.AiService
import org.l4j.template.l4j_macro.agentic.AgenticBridge

import scala.annotation.experimental
import scala.jdk.CollectionConverters.*

/**
 * Sequential agentic workflow built on macro-generated agents.
 *
 * Pipeline mirrors the langchain4j novel-writing tutorial (CreativeWriter →
 * AudienceEditor → StyleEditor with a shared `story` scope key) but every
 * agent's chat body is emitted at compile time by `AiService.materialize`
 * — no langchain4j AiServices Proxy, no runtime annotation scanning for the
 * chat logic. The `AgenticBridge` Proxy wrapper is only there so the
 * orchestrator can see the trait's `@Agent` / `@V` metadata.
 *
 * Run with:
 *   sbt "runMain org.l4j.template.l4j_macro.demo.agentic.SequentialDemoMain"
 */
@experimental
object SequentialDemoMain:

  def main(args: Array[String]): Unit =
    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val model = OpenAiChatModel.builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    // Macro-generated typed impls (compile-time {{var}} validation).
    val writerImpl   = AiService.materialize[CreativeWriter](model)
    val audienceImpl = AiService.materialize[AudienceEditor](model)
    val styleImpl    = AiService.materialize[StyleEditor](model)

    // Look the `@Agent`-bearing method up on the trait (where annotations are
    // visible) and bundle it with the macro impl as an AgentExecutor. The
    // orchestrator's subAgents(...) short-circuits when given AgentExecutors.
    val writer   = AgenticBridge.asAgent(writerImpl)
    val audience = AgenticBridge.asAgent(audienceImpl)
    val style    = AgenticBridge.asAgent(styleImpl)

    // Compose with the standard agentic orchestrator. From here on, this is
    // identical to a workflow built from langchain4j-native AiServices proxies.
    val novelCreator: UntypedAgent = AgenticServices
      .sequenceBuilder()
      .subAgents(writer, audience, style)
      .outputKey("story")
      .build()

    val input: java.util.Map[String, Object] = Map[String, Object](
      "topic"    -> "dragons and wizards",
      "style"    -> "fantasy",
      "audience" -> "young adults",
    ).asJava

    val started = System.nanoTime()
    val story   = novelCreator.invoke(input).asInstanceOf[String]
    val elapsed = (System.nanoTime() - started) / 1_000_000

    println("\n== Sequential pipeline (macro impls + agentic orchestrator) ==")
    println(s"input: $input")
    println("\n--- Final story ---")
    println(story)
    println(s"\n(pipeline completed in ${elapsed} ms)")
