package org.l4j.template.llm4s.agentic

import cats.MonadThrow
import cats.syntax.all.*

final class LoopWorkflow[F[_]: MonadThrow, A](
    body: Workflow[F, A, A],
    continueWhile: (A, Int, AgentScope[F]) => F[Boolean],
    maxIterations: Int,
) extends Workflow[F, A, A]:

  override def run(input: A, scope: AgentScope[F]): F[A] =
    loop(input, iteration = 0, scope)

  private def loop(current: A, iteration: Int, scope: AgentScope[F]): F[A] =
    continueWhile(current, iteration, scope).flatMap {
      case false => current.pure[F]
      case true if iteration >= maxIterations =>
        MonadThrow[F].raiseError(
          RuntimeException(s"LoopWorkflow exceeded maxIterations=$maxIterations")
        )
      case true =>
        body.run(current, scope).flatMap(next => loop(next, iteration + 1, scope))
    }

object LoopWorkflow:
  def apply[F[_]: MonadThrow, A](
      body: Workflow[F, A, A],
      continueWhile: (A, Int, AgentScope[F]) => F[Boolean],
      maxIterations: Int = 16,
  ): LoopWorkflow[F, A] =
    new LoopWorkflow(body, continueWhile, maxIterations)

