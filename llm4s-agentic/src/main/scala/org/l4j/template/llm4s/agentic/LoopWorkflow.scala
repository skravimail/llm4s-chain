package org.l4j.template.llm4s.agentic

import cats.MonadThrow
import cats.syntax.all.*

final class LoopWorkflow[F[_]: MonadThrow, A](
    body: Workflow[F, A, A],
    continueWhile: (A, Int, AgentScope[F]) => F[Boolean],
    maxIterations: Int,
) extends Workflow[F, A, A]:

  override def run(input: A, scope: AgentScope[F]): F[A] =
    MonadThrow[F].tailRecM[(A, Int), A]((input, 0)) { case (current, iteration) =>
      continueWhile(current, iteration, scope).flatMap {
        case false => current.asRight[(A, Int)].pure[F]
        case true if iteration >= maxIterations =>
          MonadThrow[F].raiseError(WorkflowError.MaxIterationsExceeded(maxIterations))
        case true =>
          body.run(current, scope).map(next => (next, iteration + 1).asLeft[A])
      }
    }

object LoopWorkflow:
  def apply[F[_]: MonadThrow, A](
      body: Workflow[F, A, A],
      continueWhile: (A, Int, AgentScope[F]) => F[Boolean],
      maxIterations: Int = 16,
  ): LoopWorkflow[F, A] =
    new LoopWorkflow(body, continueWhile, maxIterations)
