package org.l4j.template.llm4s.agentic

import cats.effect.Sync
import cats.syntax.flatMap.*

trait Agent[F[_], In, Out]:
  def name: String
  def run(input: In, scope: AgentScope[F]): F[Out]

  def workflow: Workflow[F, In, Out] =
    new AgentWorkflow(this)

object Agent:
  def lift[F[_], In, Out](
      agentName: String
  )(runAgent: (In, AgentScope[F]) => F[Out]): Agent[F, In, Out] =
    new Agent[F, In, Out]:
      override val name: String = agentName
      override def run(input: In, scope: AgentScope[F]): F[Out] =
        runAgent(input, scope)

  def liftScoped[F[_]: Sync, In, Out](
      agentName: String
  )(runAgent: (In, AgentScope[F]) => F[Out]): Agent[F, In, Out] =
    lift(agentName) { (input, scope) =>
      runAgent(input, scope).flatTap(output => scope.put(agentName, output))
    }

private final class AgentWorkflow[F[_], In, Out](
    agent: Agent[F, In, Out]
) extends Workflow[F, In, Out]:
  override def run(input: In, scope: AgentScope[F]): F[Out] =
    agent.run(input, scope)
