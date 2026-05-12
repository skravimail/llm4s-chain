package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.syntax.all.*

final case class RetryPolicy(
    maxAttempts: Int,
    shouldRetry: Throwable => Boolean,
):
  require(maxAttempts >= 1, "RetryPolicy maxAttempts must be at least 1")

  def run[F[_]: MonadThrow, A](effect: => F[A]): F[A] =
    def loop(attempt: Int): F[A] =
      effect.handleErrorWith { error =>
        if attempt < maxAttempts && shouldRetry(error) then loop(attempt + 1)
        else MonadThrow[F].raiseError(error)
      }

    loop(1)

object RetryPolicy:
  val noRetry: RetryPolicy =
    RetryPolicy(maxAttempts = 1, shouldRetry = _ => false)

  def retryAll(maxAttempts: Int): RetryPolicy =
    RetryPolicy(maxAttempts = maxAttempts, shouldRetry = _ => true)

