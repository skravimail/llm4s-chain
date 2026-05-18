package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.agentic.AgentScope
import org.l4j.template.llm4s.agentic.WorkflowAgent

class WorkflowRunnableSpec extends FunSuite:

  test("workflow adapter creates a fresh scope for each run") {
    val workflow =
      WorkflowAgent
        .lift[IO, Unit, Int]("counter") { (_, scope) =>
          scope.get[Int]("count").flatMap { previous =>
            val next = previous.getOrElse(0) + 1
            scope.put("count", next).map(_ => next)
          }
        }
        .workflow

    val runnable = WorkflowRunnable(workflow)

    val first = runnable.run((), RunnableSpecSupport.stubContext).unsafeRunSync()
    val second = runnable.run((), RunnableSpecSupport.stubContext).unsafeRunSync()

    assertEquals(first, 1)
    assertEquals(second, 1)
  }

  test("workflow adapter can reuse a caller-provided scope") {
    val workflow =
      WorkflowAgent
        .lift[IO, Unit, Int]("counter") { (_, scope) =>
          scope.get[Int]("count").flatMap { previous =>
            val next = previous.getOrElse(0) + 1
            scope.put("count", next).map(_ => next)
          }
        }
        .workflow

    val result = for
      scope <- AgentScope.create[IO]
      runnable = WorkflowRunnable.withScope(workflow, scope)
      first <- runnable.run((), RunnableSpecSupport.stubContext)
      second <- runnable.run((), RunnableSpecSupport.stubContext)
    yield (first, second)

    assertEquals(result.unsafeRunSync(), (1, 2))
  }

  test("workflow adapter preserves in-run scope sharing") {
    val writer = WorkflowAgent.liftScoped[IO, Int, Int]("writer") { (input, _) =>
      IO.pure(input + 1)
    }
    val reader = WorkflowAgent.lift[IO, Int, Int]("reader") { (_, scope) =>
      scope.get[Int]("writer").map(_.getOrElse(-1))
    }

    val runnable = WorkflowRunnable(writer.workflow.andThen(reader.workflow))

    val result = runnable.run(4, RunnableSpecSupport.stubContext).unsafeRunSync()

    assertEquals(result, 5)
  }
