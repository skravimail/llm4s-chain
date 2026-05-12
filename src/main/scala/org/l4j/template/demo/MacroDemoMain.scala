package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.macros.AiService
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.tools.SchemaEncoder.given
import org.l4j.template.llm4s.tools.ToolDefinition
import org.l4j.template.llm4s.tools.ValueDecoder.given

import scala.annotation.experimental

final case class DefineArgs(term: String)

object WikiLookup:
  def define(term: String): String =
    term.toLowerCase match
      case "monad"   => "A monad is a design pattern for sequencing effectful computations."
      case "functor" => "A functor maps elements of one set to another while preserving structure."
      case "fiber"   => "A fiber is a lightweight, cooperatively-scheduled unit of concurrent execution."
      case _         => s"No definition found for '$term'"

@experimental
object MacroDemoMain extends IOApp.Simple:

  override def run: IO[Unit] =
    BackendSupport.fromEnv.use { backend =>
      val assistant = AiService.materialize[Assistant](backend)
      val reviewer  = AiService.materialize[Reviewer](backend)

      val defineTool: ToolDefinition[IO] =
        ToolDefinition.fromProduct[IO, DefineArgs](
          name = "define",
          description = "Look up a one-line definition for a programming term",
        ) { args =>
          IO.pure(ToolResult.Text(WikiLookup.define(args.term)))
        }
      val tutorTools: ToolKit[IO] = defineTool.toToolKit

      val tutor = AiService.materialize[Tutor](backend, tutorTools)

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
        _ <- IO.println("\n[1/3] Plain chat:")
        _ <- IO.println(s"  Q: What is referential transparency?")
        _ <- IO(assistant.ask("What is referential transparency? Answer in one sentence."))
               .flatMap(answer => IO.println(s"  A: $answer"))

        _ <- IO.println("\n[2/3] Typed return (decoded into CvReview case class):")
        _ <- IO(reviewer.review(cv, jd)).flatMap { review =>
          IO.println(s"  score:    ${review.score}") *>
            IO.println(s"  feedback: ${review.feedback}")
        }

        _ <- IO.println("\n[3/3] Tool-using agent (model calls `define` first, then expands):")
        _ <- IO(tutor.explain("monad")).flatMap(answer => IO.println(s"  $answer"))
      yield ()
    }
