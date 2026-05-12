package org.l4j.template.llm4s.agentic

import cats.Monad
import cats.syntax.all.*

final class ConditionalWorkflow[F[_]: Monad, In, Out](
    chooseTrue: (In, AgentScope[F]) => F[Boolean],
    ifTrue: Workflow[F, In, Out],
    ifFalse: Workflow[F, In, Out],
) extends Workflow[F, In, Out]:

  override def run(input: In, scope: AgentScope[F]): F[Out] =
    chooseTrue(input, scope).flatMap { selected =>
      if selected then ifTrue.run(input, scope)
      else ifFalse.run(input, scope)
    }

object ConditionalWorkflow:
  def apply[F[_]: Monad, In, Out](
      chooseTrue: (In, AgentScope[F]) => F[Boolean],
      ifTrue: Workflow[F, In, Out],
      ifFalse: Workflow[F, In, Out],
  ): ConditionalWorkflow[F, In, Out] =
    new ConditionalWorkflow(chooseTrue, ifTrue, ifFalse)

