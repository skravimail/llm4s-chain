package org.llm4s.template.l4j_macro.demo

import dev.langchain4j.model.openai.OpenAiChatModel
import org.llm4s.template.l4j_macro.{ AiService, Tools }

import scala.annotation.experimental

/**
 * End-to-end demo of the Scala 3 macro-based AiService layer.
 *
 *   1. Plain chat with a compile-time-validated `{{var}}` template.
 *   2. Typed case-class return decoded via uPickle (no Jackson POJO needed).
 *   3. Tool-using agent: `Tools.from` walks `@tool` methods, the macro generates
 *      ToolSpecifications + dispatch directly into the Scala instance.
 *
 * The annotation cascades to the call sites because macro-reflection APIs are
 * `@experimental` in Scala 3.3 LTS.
 *
 * Run with:
 *   sbt "runMain org.llm4s.template.l4j_macro.demo.MacroDemoMain"
 *
 * Override the model with env vars `LANGCHAIN4J_BASE_URL` / `LANGCHAIN4J_API_KEY`
 * / `LANGCHAIN4J_MODEL`.
 */
@experimental
object  MacroDemoMain:

  def main(args: Array[String]): Unit =
    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val model = OpenAiChatModel.builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    // --- 1. Plain chat ---------------------------------------------------------
    val assistant = AiService.materialize[Assistant](model)
    println("\n[1/3] Plain chat:")
    println(s"  Q: What is referential transparency? (one sentence)")
    println(s"  A: ${assistant.ask("What is referential transparency? Answer in one sentence.")}")

    // --- 2. Typed return (case class via uPickle) -----------------------------
    val reviewer = AiService.materialize[Reviewer](model)
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

    val review = reviewer.review(cv, jd)
    println("\n[2/3] Typed return (decoded into CvReview case class):")
    println(s"  score:    ${review.score}")
    println(s"  feedback: ${review.feedback}")

    // --- 3. Tool-using agent --------------------------------------------------
    val toolkit = Tools.from(new WikiLookup)
    val tutor   = AiService.materialize[Tutor](model, toolkit)
    println("\n[3/3] Tool-using agent (model calls `define` first, then expands):")
    println(s"  ${tutor.explain("monad")}")
