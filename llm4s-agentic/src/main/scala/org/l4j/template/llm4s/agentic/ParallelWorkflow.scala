package org.l4j.template.llm4s.agentic

import cats.Monad
import cats.Parallel
import cats.syntax.parallel.*

final class ParallelWorkflow[F[_]: Monad: Parallel, In, LeftOut, RightOut](
    left: Workflow[F, In, LeftOut],
    right: Workflow[F, In, RightOut],
) extends Workflow[F, In, (LeftOut, RightOut)]:

  override def run(input: In, scope: AgentScope[F]): F[(LeftOut, RightOut)] =
    (left.run(input, scope), right.run(input, scope)).parTupled

object ParallelWorkflow:
  def apply[F[_]: Monad: Parallel, In, LeftOut, RightOut](
      left: Workflow[F, In, LeftOut],
      right: Workflow[F, In, RightOut],
  ): ParallelWorkflow[F, In, LeftOut, RightOut] =
    new ParallelWorkflow(left, right)
