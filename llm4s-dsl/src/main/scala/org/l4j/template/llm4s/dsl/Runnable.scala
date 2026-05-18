package org.l4j.template.llm4s.dsl

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.Parallel
import cats.syntax.parallel.*

trait Runnable[F[_], -In, +Out]:
  def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out]

trait RunValue[F[_], +A]:
  type Value <: A
  protected[dsl] def underlying: F[Value]

object RunValue:
  def apply[F[_], A](fa: F[A]): RunValue[F, A] =
    new RunValue[F, A]:
      override type Value = A
      override protected[dsl] val underlying: F[A] = fa

extension [F[_]: Functor, A](value: RunValue[F, A])
  def toF: F[A] =
    Functor[F].map(value.underlying)(identity)

object Runnable:
  def fromFunction[F[_]: Applicative, In, Out](f: In => Out): Runnable[F, In, Out] =
    eval((input, _) => Applicative[F].pure(f(input)))

  def eval[F[_], In, Out](f: (In, RunContext[F]) => F[Out]): Runnable[F, In, Out] =
    new Runnable[F, In, Out]:
      override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out] =
        RunValue(f(input, ctx))

  def pure[F[_]: Applicative, Out](value: Out): Runnable[F, Any, Out] =
    fromFunction[F, Any, Out](_ => value)

extension [F[_]: Functor, In, Out](self: Runnable[F, In, Out])
  def run(input: In, ctx: RunContext[F]): F[Out] =
    self.runValue(input, ctx).toF

extension [F[_]: Monad, In, Out](self: Runnable[F, In, Out])
  def andThen[Next](next: Runnable[F, Out, Next]): Runnable[F, In, Next] =
    Runnable.eval { (input, ctx) =>
      Monad[F].flatMap(self.run(input, ctx))(output => next.run(output, ctx))
    }

  def map[Next](f: Out => Next): Runnable[F, In, Next] =
    Runnable.eval { (input, ctx) =>
      Monad[F].map(self.run(input, ctx))(f)
    }

extension [F[_], In, Out](self: Runnable[F, In, Out])
  def contramap[Before](f: Before => In): Runnable[F, Before, Out] =
    new Runnable[F, Before, Out]:
      override def runValue(input: Before, ctx: RunContext[F]): RunValue[F, Out] =
        self.runValue(f(input), ctx)

  def named(label: String): NamedRunnable[F, In, Out] =
    NamedRunnable(label, self)

extension [F[_]: Monad: Parallel, In, Out](self: Runnable[F, In, Out])
  def zipPar[In0 <: In, Other](right: Runnable[F, In0, Other]): Runnable[F, In0, (Out, Other)] =
    Runnable.eval { (input, ctx) =>
      (self.run(input, ctx), right.run(input, ctx)).parTupled
    }

final case class NamedRunnable[F[_], -In, +Out](
    label: String,
    underlying: Runnable[F, In, Out],
) extends Runnable[F, In, Out]:
  override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out] =
    underlying.runValue(input, ctx)
