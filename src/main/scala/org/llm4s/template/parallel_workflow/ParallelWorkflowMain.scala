package org.llm4s.template.parallel_workflow

import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.AiServices

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

/**
 * Parallel CV-review workflow using two langchain4j AiServices agents.
 *
 * Defaults point at the local OMLX server already wired in this template
 * (`OMLX_API_KEY` + `http://localhost:8000/v1`). Override via env vars
 * `LANGCHAIN4J_BASE_URL`, `LANGCHAIN4J_API_KEY`, `LANGCHAIN4J_MODEL` to
 * point at OpenAI / any OpenAI-compatible endpoint.
 *
 * Run with:
 *   sbt "runMain org.llm4s.template.parallel_workflow.ParallelWorkflowMain"
 */
object ParallelWorkflowMain {

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val model = OpenAiChatModel.builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    val manager: ManagerCvReviewer =
      AiServices.builder(classOf[ManagerCvReviewer]).chatModel(model).build()
    val tech: TechnicalCvReviewer =
      AiServices.builder(classOf[TechnicalCvReviewer]).chatModel(model).build()

    val cv =
      """Jane Doe — Senior Scala Engineer
        |10 years on the JVM, 6 years primary Scala. Strong with Akka, cats-effect, ZIO.
        |Led the backend platform team (8 engineers) at FinCorp through a migration
        |from a Play monolith to event-sourced microservices on Kafka + Cassandra.
        |Published two internal libraries (HTTP client, retry/backoff toolkit).
        |Speaks at Scala meetups; contributor to sttp.
        |Education: BS Computer Science, somewhere.""".stripMargin

    val jobDescription =
      """Senior Backend Engineer at an early-stage fintech startup.
        |Stack: Scala 2.13/3, cats-effect, http4s, Postgres, Kafka, Kubernetes.
        |Looking for: production ownership, strong testing discipline, ability to
        |make pragmatic architectural calls, comfort across the stack from infra to API.""".stripMargin

    val started = System.nanoTime()

    val managerF = Future(manager.reviewCv(cv, jobDescription))
    val techF    = Future(tech.reviewCv(cv, jobDescription))

    val (managerReview, techReview) =
      Await.result(managerF.zip(techF), 5.minutes)

    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    println(s"\n== Manager review ==")
    println(s"score: ${managerReview.score}")
    println(s"feedback: ${managerReview.feedback}")
    println(s"\n== Technical review ==")
    println(s"score: ${techReview.score}")
    println(s"feedback: ${techReview.feedback}")
    println(s"\n(both reviews completed in parallel in ${elapsedMs} ms)")
  }
}
