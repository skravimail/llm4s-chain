package org.l4j.template.llm4s.agentic

import cats.Monad
import cats.syntax.all.*

final class SequenceWorkflow[F[_]: Monad, In, Mid, Out](
    first: Workflow[F, In, Mid],
    second: Workflow[F, Mid, Out],
) extends Workflow[F, In, Out]:

  override def run(input: In, scope: AgentScope[F]): F[Out] =
    first.run(input, scope).flatMap(second.run(_, scope))

object SequenceWorkflow:
  def apply[F[_]: Monad, In, Mid, Out](
      first: Workflow[F, In, Mid],
      second: Workflow[F, Mid, Out],
  ): SequenceWorkflow[F, In, Mid, Out] =
    new SequenceWorkflow(first, second)

