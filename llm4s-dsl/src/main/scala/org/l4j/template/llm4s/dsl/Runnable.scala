package org.l4j.template.llm4s.dsl

import cats.Applicative
import cats.Monad
import cats.Parallel
import cats.syntax.parallel.*

trait Runnable[F[_], In, Out]:
  self =>

  def run(input: In, ctx: RunContext[F]): F[Out]

  def andThen[Next](next: Runnable[F, Out, Next])(using Monad[F]): Runnable[F, In, Next] =
    Runnable.eval { (input, ctx) =>
      Monad[F].flatMap(self.run(input, ctx))(output => next.run(output, ctx))
    }

  def map[Next](f: Out => Next)(using Monad[F]): Runnable[F, In, Next] =
    Runnable.eval { (input, ctx) =>
      Monad[F].map(self.run(input, ctx))(f)
    }

  def contramap[Before](f: Before => In): Runnable[F, Before, Out] =
    Runnable.eval { (input, ctx) =>
      self.run(f(input), ctx)
    }

  def zipPar[Other](right: Runnable[F, In, Other])(using Monad[F], Parallel[F]): Runnable[F, In, (Out, Other)] =
    Runnable.eval { (input, ctx) =>
      (self.run(input, ctx), right.run(input, ctx)).parTupled
    }

  def named(label: String): NamedRunnable[F, In, Out] =
    NamedRunnable(label, self)

object Runnable:
  def fromFunction[F[_]: Applicative, In, Out](f: In => Out): Runnable[F, In, Out] =
    eval((input, _) => Applicative[F].pure(f(input)))

  def eval[F[_], In, Out](f: (In, RunContext[F]) => F[Out]): Runnable[F, In, Out] =
    new Runnable[F, In, Out]:
      override def run(input: In, ctx: RunContext[F]): F[Out] =
        f(input, ctx)

  def pure[F[_]: Applicative, Out](value: Out): Runnable[F, Any, Out] =
    fromFunction[F, Any, Out](_ => value)

final case class NamedRunnable[F[_], In, Out](
    label: String,
    underlying: Runnable[F, In, Out],
) extends Runnable[F, In, Out]:
  override def run(input: In, ctx: RunContext[F]): F[Out] =
    underlying.run(input, ctx)
