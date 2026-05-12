package org.l4j.template.demo.agentic

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.demo.BackendSupport
import org.l4j.template.llm4s.agentic.Agent
import org.l4j.template.llm4s.agentic.AgentScope
import org.l4j.template.llm4s.agentic.ParallelWorkflow
import org.l4j.template.llm4s.macros.AiService

import scala.annotation.experimental

@experimental
object ParallelDemoMain extends IOApp.Simple:

  override def run: IO[Unit] =
    BackendSupport.fromEnv.use { backend =>
      val manager   = AiService.materialize[ManagerReviewer](backend)
      val technical = AiService.materialize[TechnicalReviewer](backend)

      val managerAgent = Agent.liftScoped[IO, CvUnderReview, CvScoredReview]("managerReview") { (input, _) =>
        IO(manager.reviewCv(input.candidateCv, input.jobDescription))
      }
      val technicalAgent = Agent.liftScoped[IO, CvUnderReview, CvScoredReview]("technicalReview") { (input, _) =>
        IO(technical.reviewCv(input.candidateCv, input.jobDescription))
      }

      val workflow = ParallelWorkflow(managerAgent.workflow, technicalAgent.workflow)

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

      val input = CvUnderReview(candidateCv = cv, jobDescription = jd)

      for
        scope   <- AgentScope.create[IO]
        started <- IO(System.nanoTime())
        result  <- workflow.run(input, scope)
        elapsed <- IO((System.nanoTime() - started) / 1_000_000)
        (managerReview, techReview) = result
        _ <- IO.println("\n== Parallel pipeline (two native reviewers, run concurrently) ==")
        _ <- IO.println(s"\n--- Manager review ---")
        _ <- IO.println(s"  score:    ${managerReview.score}")
        _ <- IO.println(s"  feedback: ${managerReview.feedback}")
        _ <- IO.println(s"\n--- Technical review ---")
        _ <- IO.println(s"  score:    ${techReview.score}")
        _ <- IO.println(s"  feedback: ${techReview.feedback}")
        _ <- IO.println(s"\n(both reviews completed in $elapsed ms — would be ~2× this if sequential)")
      yield ()
    }
