package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.structured.AiAgent
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ToolDefinition
import org.l4j.template.llm4s.tools.ValueDecoder

final case class CvReview(score: Int, feedback: String)
    derives StructuredCodec,
      SchemaEncoder,
      ValueDecoder

final case class DefineArgs(term: String) derives SchemaEncoder, ValueDecoder

object WikiLookup:
  def define(term: String): String =
    term.toLowerCase match
      case "monad"   => "A monad is a design pattern for sequencing effectful computations."
      case "functor" => "A functor maps elements of one set to another while preserving structure."
      case "fiber"   => "A fiber is a lightweight, cooperatively-scheduled unit of concurrent execution."
      case _         => s"No definition found for '$term'"

object AgentDemoMain extends IOApp.Simple:

  private val assistantSystem =
    "You are a helpful programming tutor. Answer concisely."

  private val reviewerSystem =
    "You are a hiring manager. Score the CV (0-100) against the job description and give terse feedback. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown, no prose."

  private val tutorSystem =
    "You are a programming tutor. When the user asks about a term, call the `define` tool first to get " +
      "a precise definition, then expand on it in one or two sentences."

  private def ask(agent: AiAgent[IO], question: String): IO[String] =
    agent.chat(
      system = assistantSystem,
      user = s"Question: $question",
    )

  private def review(agent: AiAgent[IO], cv: String, jd: String): IO[CvReview] =
    agent.chatAs[CvReview](
      system = reviewerSystem,
      user = s"Job description:\n$jd\n\nCandidate CV:\n$cv",
    )

  private def explain(agent: AiAgent[IO], topic: String): IO[String] =
    agent.chat(
      system = tutorSystem,
      user = s"Explain: $topic",
    )

  private def defineTool: ToolDefinition[IO] =
    ToolDefinition.fromProduct[IO, DefineArgs](
      name = "define",
      description = "Look up a one-line definition for a programming term",
    ) { args =>
      IO.pure(ToolResult.Text(WikiLookup.define(args.term)))
    }

  override def run: IO[Unit] =
    BackendSupport.fromConfig.use { case (backend, bundle, _) =>
      val plain: AiAgent[IO] = AiAgent[IO](backend, ToolKit.empty[IO], RuntimeConfig(), bundle.runtime)
      val tooled: AiAgent[IO] = plain.withTools(defineTool.toToolKit)

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

      for
        _      <- IO.println("\n[1/3] Plain chat:")
        _      <- IO.println("  Q: What is referential transparency?")
        answer <- ask(plain, "What is referential transparency? Answer in one sentence.")
        _      <- IO.println(s"  A: $answer")

        _      <- IO.println("\n[2/3] Typed return (decoded into CvReview case class):")
        cvOut  <- review(plain, cv, jd)
        _      <- IO.println(s"  score:    ${cvOut.score}")
        _      <- IO.println(s"  feedback: ${cvOut.feedback}")

        _      <- IO.println("\n[3/3] Tool-using agent (model calls `define` first, then expands):")
        topic  <- explain(tooled, "monad")
        _      <- IO.println(s"  $topic")
      yield ()
    }
