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

    val error = intercept[WorkflowError.MaxIterationsExceeded] {
      AgentScope.create[IO].flatMap(workflow.run(0, _)).unsafeRunSync()
    }

    assertEquals(error.getMessage, "LoopWorkflow exceeded maxIterations=2")
  }

  test("loop workflow remains stack-safe across many iterations") {
    val increment = Agent.lift[IO, Int, Int]("increment")((value, _) => IO.pure(value + 1))
    val workflow = LoopWorkflow[IO, Int](
      body = increment.workflow,
      continueWhile = (value, _, _) => IO.pure(value < 5000),
      maxIterations = 6000,
    )

    val result = AgentScope.create[IO].flatMap(workflow.run(0, _)).unsafeRunSync()
    assertEquals(result, 5000)
  }

  test("supervisor agent plans and invokes subagents dynamically") {
    val summary = Agent.lift[IO, String, String]("summary")((input, _) => IO.pure(s"summary:$input"))
    val critic = Agent.lift[IO, String, String]("critic")((input, _) => IO.pure(s"critic:$input"))
    val registry = SubAgentRegistry.of(summary, critic)
    val planner = SupervisorAgent.planner[IO, String, String] { (input, _, _) =>
      IO.pure(
        List(
          PlanStep("summary", outputKey = Some("summaryOutput")),
          PlanStep("critic", input = Some(s"review $input"), outputKey = Some("criticOutput")),
        )
      )
    }
    val supervisor = SupervisorAgent[IO, String, String](
      name = "supervisor",
      registry = registry,
      planner = planner,
      aggregate = (_, results, _) => IO.pure(results.map(_.output).mkString(" | ")),
    )

    val program = for
      scope <- AgentScope.create[IO]
      result <- supervisor.run("Scala", scope)
      summaryOutput <- scope.get[String]("summaryOutput")
      criticOutput <- scope.get[String]("criticOutput")
    yield (result, summaryOutput, criticOutput)

    val (result, summaryOutput, criticOutput) = program.unsafeRunSync()

    assertEquals(result, "summary:Scala | critic:review Scala")
    assertEquals(summaryOutput, Some("summary:Scala"))
    assertEquals(criticOutput, Some("critic:review Scala"))
  }

  test("supervisor agent fails when a planned subagent is missing") {
    val registry = SubAgentRegistry.of(Agent.lift[IO, String, String]("summary")((input, _) => IO.pure(input)))
    val planner = SupervisorAgent.planner[IO, String, String] { (_, _, _) =>
      IO.pure(List(PlanStep("missing")))
    }
    val supervisor = SupervisorAgent[IO, String, String](
      name = "supervisor",
      registry = registry,
      planner = planner,
      aggregate = (_, results, _) => IO.pure(results.map(_.output).mkString),
    )

    val error = intercept[WorkflowError.MissingSubAgent] {
      AgentScope.create[IO].flatMap(supervisor.run("Scala", _)).unsafeRunSync()
    }

    assertEquals(error.getMessage, "SupervisorAgent 'supervisor' could not find sub-agent 'missing'")
  }
