package org.l4j.template.llm4s.dsl

import cats.Monad
import cats.effect.Sync
import org.l4j.template.llm4s.agentic.AgentScope
import org.l4j.template.llm4s.agentic.Workflow
import org.l4j.template.llm4s.agentic.WorkflowAgent

/** Workflow interop for the DSL.
  *
  * `Workflow` remains the stateful orchestration abstraction owned by
  * `llm4s-agentic`. The DSL does not compile generic `Runnable` graphs down
  * into `Workflow`; instead, workflows are adapted into runnable nodes when a
  * caller wants to embed agentic orchestration inside a broader typed chain.
  */
object WorkflowRunnable:
  def apply[F[_]: Sync, In, Out](
      workflow: Workflow[F, In, Out]
  ): Runnable[F, In, Out] =
    Runnable.leaf("workflow-adapter") { (input, _) =>
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
    Runnable.leaf("workflow-shared-scope") { (input, _) =>
      workflow.run(input, scope)
    }
