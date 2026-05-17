package org.l4j.template.llm4s.agentic

import cats.effect.Sync
import cats.syntax.flatMap.*

/** A workflow-style agent: a named function `In => F[Out]` with access to a
  * shared [[AgentScope]]. Renamed from `Agent` (PR-17) to disambiguate from
  * `llm4s-structured`'s `AiAgent[F]`, which is a different concept entirely
  * (an LLM-backed chat facade). The legacy name `Agent` remains as an alias
  * below so existing call sites keep compiling.
  */
trait WorkflowAgent[F[_], In, Out]:
  def name: String
  def run(input: In, scope: AgentScope[F]): F[Out]

  def workflow: Workflow[F, In, Out] =
    new AgentWorkflow(this)

object WorkflowAgent:
  def lift[F[_], In, Out](
      agentName: String
  )(runAgent: (In, AgentScope[F]) => F[Out]): WorkflowAgent[F, In, Out] =
    new WorkflowAgent[F, In, Out]:
      override val name: String = agentName
      override def run(input: In, scope: AgentScope[F]): F[Out] =
        runAgent(input, scope)

  def liftScoped[F[_]: Sync, In, Out](
      agentName: String
  )(runAgent: (In, AgentScope[F]) => F[Out]): WorkflowAgent[F, In, Out] =
    lift(agentName) { (input, scope) =>
      runAgent(input, scope).flatTap(output => scope.put(agentName, output))
    }

/** Backwards-compatible alias kept after the PR-17 rename. New code should
  * prefer `WorkflowAgent` to make the distinction with
  * `org.l4j.template.llm4s.structured.AiAgent` explicit.
  */
type Agent[F[_], In, Out] = WorkflowAgent[F, In, Out]
val Agent: WorkflowAgent.type = WorkflowAgent

private final class AgentWorkflow[F[_], In, Out](
    agent: WorkflowAgent[F, In, Out]
) extends Workflow[F, In, Out]:
  override def run(input: In, scope: AgentScope[F]): F[Out] =
    agent.run(input, scope)
