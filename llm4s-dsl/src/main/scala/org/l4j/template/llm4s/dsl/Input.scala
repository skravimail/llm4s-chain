package org.l4j.template.llm4s.dsl

import cats.Applicative

object Input:
  def apply[F[_]: Applicative, A]: Runnable[F, A, A] =
    Runnable.leaf("input") { (input, _) =>
      Applicative[F].pure(input)
    }

  def pick[F[_]: Applicative, In, Out](label: String)(f: In => Out): Runnable[F, In, Out] =
    Runnable.leaf(s"input-$label") { (input, _) =>
      Applicative[F].pure(f(input))
    }
