package org.l4j.template.llm4s.agentic

import cats.Monad

trait Workflow[F[_], In, Out]:
  def run(input: In, scope: AgentScope[F]): F[Out]

  def andThen[Next](next: Workflow[F, Out, Next])(using Monad[F]): Workflow[F, In, Next] =
    SequenceWorkflow(this, next)

object Workflow:
  def fromAgent[F[_], In, Out](agent: Agent[F, In, Out]): Workflow[F, In, Out] =
    agent.workflow

