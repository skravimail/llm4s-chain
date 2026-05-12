package org.l4j.template.llm4s.agentic

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

class AgenticWorkflowSpec extends FunSuite:

  test("sequence workflow passes each agent output to the next step") {
    val writer = Agent.liftScoped[IO, String, String]("writer") { (topic, _) =>
      IO.pure(s"story about $topic")
    }
    val editor = Agent.liftScoped[IO, String, String]("editor") { (story, _) =>
      IO.pure(story.toUpperCase)
    }

    val workflow = writer.workflow.andThen(editor.workflow)

    val program = for
      scope <- AgentScope.create[IO]
      result <- workflow.run("scala", scope)
      writerOutput <- scope.get[String]("writer")
      editorOutput <- scope.get[String]("editor")
    yield (result, writerOutput, editorOutput)

    val (result, writerOutput, editorOutput) = program.unsafeRunSync()

    assertEquals(result, "STORY ABOUT SCALA")
    assertEquals(writerOutput, Some("story about scala"))
    assertEquals(editorOutput, Some("STORY ABOUT SCALA"))
  }

  test("parallel workflow runs independent branches over the same input") {
    val manager = Agent.liftScoped[IO, String, Int]("managerReview") { (cv, _) =>
      IO.pure(cv.length)
    }
    val technical = Agent.liftScoped[IO, String, Boolean]("technicalReview") { (cv, _) =>
      IO.pure(cv.contains("Scala"))
    }

    val workflow = ParallelWorkflow(manager.workflow, technical.workflow)

    val program = for
      scope <- AgentScope.create[IO]
      result <- workflow.run("Scala engineer", scope)
      managerOutput <- scope.get[Int]("managerReview")
      technicalOutput <- scope.get[Boolean]("technicalReview")
    yield (result, managerOutput, technicalOutput)

    val (result, managerOutput, technicalOutput) = program.unsafeRunSync()

    assertEquals(result, (14, true))
    assertEquals(managerOutput, Some(14))
    assertEquals(technicalOutput, Some(true))
  }

  test("conditional workflow selects the matching branch") {
    val positive = Agent.lift[IO, Int, String]("positive")((value, _) => IO.pure(s"positive:$value"))
    val nonPositive = Agent.lift[IO, Int, String]("nonPositive")((value, _) => IO.pure(s"non-positive:$value"))
    val workflow = ConditionalWorkflow[IO, Int, String](
      chooseTrue = (value, _) => IO.pure(value > 0),
      ifTrue = positive.workflow,
      ifFalse = nonPositive.workflow,
    )

    val result = AgentScope.create[IO].flatMap(workflow.run(-1, _)).unsafeRunSync()

    assertEquals(result, "non-positive:-1")
  }

  test("loop workflow repeats until the continuation predicate stops") {
    val increment = Agent.liftScoped[IO, Int, Int]("increment")((value, _) => IO.pure(value + 1))
    val workflow = LoopWorkflow[IO, Int](
      body = increment.workflow,
      continueWhile = (value, _, _) => IO.pure(value < 3),
      maxIterations = 5,
    )

    val program = for
      scope <- AgentScope.create[IO]
      result <- workflow.run(0, scope)
      lastIncrement <- scope.get[Int]("increment")
    yield (result, lastIncrement)

    assertEquals(program.unsafeRunSync(), (3, Some(3)))
  }

  test("loop workflow fails when max iterations are exceeded") {
    val increment = Agent.lift[IO, Int, Int]("increment")((value, _) => IO.pure(value + 1))
    val workflow = LoopWorkflow[IO, Int](
      body = increment.workflow,
      continueWhile = (_, _, _) => IO.pure(true),
      maxIterations = 2,
    )

    val error = intercept[RuntimeException] {
      AgentScope.create[IO].flatMap(workflow.run(0, _)).unsafeRunSync()
    }

    assertEquals(error.getMessage, "LoopWorkflow exceeded maxIterations=2")
  }

