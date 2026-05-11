package org.llm4s.template.sequential_workflow

import dev.langchain4j.agentic.{ AgenticServices, UntypedAgent }
import dev.langchain4j.model.openai.OpenAiChatModel

import scala.jdk.CollectionConverters._

/**
 * Sequential agent workflow mirroring the langchain4j docs novel-writing example
 * (https://docs.langchain4j.dev/tutorials/agents).
 *
 * Pipeline: CreativeWriter → AudienceEditor → StyleEditor. Each agent writes its
 * output under the shared `story` key, which the next agent reads via `{{story}}`.
 *
 * Defaults point at the local OMLX server already wired in this template. Override
 * with env vars `LANGCHAIN4J_BASE_URL`, `LANGCHAIN4J_API_KEY`, `LANGCHAIN4J_MODEL`.
 *
 * Run with:
 *   sbt "runMain org.llm4s.template.sequential_workflow.SequentialWorkflowMain"
 */
object SequentialWorkflowMain {

  def main(args: Array[String]): Unit = {
    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val model = OpenAiChatModel.builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    val creativeWriter = AgenticServices
      .agentBuilder(classOf[CreativeWriter])
      .chatModel(model)
      .build()

    val audienceEditor = AgenticServices
      .agentBuilder(classOf[AudienceEditor])
      .chatModel(model)
      .build()

    val styleEditor = AgenticServices
      .agentBuilder(classOf[StyleEditor])
      .chatModel(model)
      .build()

    val novelCreator: UntypedAgent = AgenticServices
      .sequenceBuilder()
      .subAgents(creativeWriter, audienceEditor, styleEditor)
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

    println("\n== Sequential pipeline: writer → audience editor → style editor ==")
    println(s"input: $input")
    println(s"\n--- Final story ---")
    println(story)
    println(s"\n(pipeline completed in ${elapsed} ms)")
  }
}