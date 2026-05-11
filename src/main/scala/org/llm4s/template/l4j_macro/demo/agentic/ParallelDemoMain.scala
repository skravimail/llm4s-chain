package org.llm4s.template.l4j_macro.demo.agentic

import dev.langchain4j.agentic.{ AgenticServices, UntypedAgent }
import dev.langchain4j.model.openai.OpenAiChatModel
import org.llm4s.template.l4j_macro.AiService
import org.llm4s.template.l4j_macro.agentic.AgenticBridge

import scala.annotation.experimental
import scala.jdk.CollectionConverters.*

/**
 * Parallel agentic workflow: ManagerReviewer and TechnicalReviewer score the
 * same CV against the same job description concurrently. Each writes to its
 * own scope key (`managerReview` / `technicalReview`), so we read both out of
 * the AgenticScope after the workflow completes.
 *
 * Each reviewer's chat body is macro-generated (compile-time `{{var}}`
 * validation, typed `CvScoredReview` return via uPickle). The parallel
 * orchestration is langchain4j-agentic's standard `parallelBuilder()`.
 *
 * Run with:
 *   sbt "runMain org.llm4s.template.l4j_macro.demo.agentic.ParallelDemoMain"
 */
@experimental
object ParallelDemoMain:

  def main(args: Array[String]): Unit =
    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val model = OpenAiChatModel.builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    val managerImpl   = AiService.materialize[ManagerReviewer](model)
    val technicalImpl = AiService.materialize[TechnicalReviewer](model)

    val manager   = AgenticBridge.asAgent(managerImpl)
    val technical = AgenticBridge.asAgent(technicalImpl)

    val reviewer: UntypedAgent = AgenticServices
      .parallelBuilder()
      .subAgents(manager, technical)
      .build()

    val cv =
      """Jane Doe — Senior Scala Engineer.
        |10 years on the JVM, 6 years primary Scala. Strong with Akka, cats-effect, ZIO.
        |Led an 8-engineer platform team migrating a Play monolith to event-sourced
        |microservices on Kafka + Cassandra. Publishes internal libraries; speaks at
        |Scala meetups; contributor to sttp.""".stripMargin
    val jd =
      """Senior Backend Engineer at an early-stage fintech startup.
        |Stack: Scala 2.13/3, cats-effect, http4s, Postgres, Kafka, Kubernetes.
        |Looking for: production ownership, strong testing discipline, pragmatic
        |architectural calls, comfort from infra to API.""".stripMargin

    val input: java.util.Map[String, Object] = Map[String, Object](
      "candidateCv"    -> cv,
      "jobDescription" -> jd,
    ).asJava

    val started = System.nanoTime()
    val result  = reviewer.invokeWithAgenticScope(input)
    val elapsed = (System.nanoTime() - started) / 1_000_000

    val scope         = result.agenticScope()
    val managerReview = scope.readState("managerReview").asInstanceOf[CvScoredReview]
    val techReview    = scope.readState("technicalReview").asInstanceOf[CvScoredReview]

    println("\n== Parallel pipeline (two macro reviewers, run concurrently) ==")
    println(s"\n--- Manager review ---")
    println(s"  score:    ${managerReview.score}")
    println(s"  feedback: ${managerReview.feedback}")
    println(s"\n--- Technical review ---")
    println(s"  score:    ${techReview.score}")
    println(s"  feedback: ${techReview.feedback}")
    println(s"\n(both reviews completed in ${elapsed} ms — would be ~2× this if sequential)")
