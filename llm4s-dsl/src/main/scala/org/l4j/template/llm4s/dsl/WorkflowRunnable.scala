package org.l4j.template.llm4s.dsl

import cats.Monad
import cats.effect.Sync
import org.l4j.template.llm4s.agentic.AgentScope
import org.l4j.template.llm4s.agentic.Workflow
import org.l4j.template.llm4s.agentic.WorkflowAgent

object WorkflowRunnable:
  def apply[F[_]: Sync, In, Out](
      workflow: Workflow[F, In, Out]
  ): Runnable[F, In, Out] =
    Runnable.eval { (input, _) =>
      Sync[F].flatMap(AgentScope.create[F])(workflow.run(input, _))
    }

  def fromAgent[F[_]: Sync, In, Out](
      agent: WorkflowAgent[F, In, Out]
  ): Runnable[F, In, Out] =
    apply(agent.workflow)

  def withScope[F[_]: Monad, In, Out](
      workflow: Workflow[F, In, Out],
      scope: AgentScope[F],
  ): Runnable[F, In, Out] =
    Runnable.eval { (input, _) =>
      workflow.run(input, scope)
    }
