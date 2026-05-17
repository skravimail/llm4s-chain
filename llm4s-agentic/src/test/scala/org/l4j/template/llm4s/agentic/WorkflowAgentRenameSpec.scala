package org.l4j.template.llm4s.agentic

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/** Locks in the PR-17 rename: the canonical type is now `WorkflowAgent`,
  * but the legacy `Agent` alias must keep working so existing code (and the
  * other tests in this module that still spell it `Agent`) compile.
  */
class WorkflowAgentRenameSpec extends FunSuite:

  test("WorkflowAgent.lift constructs an agent the new way") {
    val agent = WorkflowAgent.lift[IO, String, String]("hi") { (s, _) =>
      IO.pure(s.reverse)
    }
    val program = for
      scope <- AgentScope.create[IO]
      out <- agent.run("abc", scope)
    yield out

    assertEquals(program.unsafeRunSync(), "cba")
    assertEquals(agent.name, "hi")
  }

  test("Agent (legacy alias) refers to the same type as WorkflowAgent") {
    val viaWorkflowAgent: WorkflowAgent[IO, Int, Int] =
      WorkflowAgent.lift("inc")((n, _) => IO.pure(n + 1))
    // Assignment-compatible both directions: alias and target are the same type.
    val viaAgentAlias: Agent[IO, Int, Int] = viaWorkflowAgent
    val backAgain: WorkflowAgent[IO, Int, Int] = viaAgentAlias

    assertEquals(backAgain.name, "inc")
  }

  test("Agent.lift (legacy companion alias) still constructs the same kind of value") {
    val a = Agent.lift[IO, String, Int]("len")((s, _) => IO.pure(s.length))
    val program = AgentScope.create[IO].flatMap(scope => a.run("hello", scope))
    assertEquals(program.unsafeRunSync(), 5)
  }
