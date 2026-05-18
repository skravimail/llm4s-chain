package org.l4j.template.llm4s.dsl

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.Parallel
import cats.syntax.parallel.*

trait Runnable[F[_], -In, +Out]:
  def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out]
  protected[dsl] def graphNode: RunnableGraph.Node

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
  private[dsl] def leaf[F[_], In, Out](
      label: String
  )(f: (In, RunContext[F]) => F[Out]): Runnable[F, In, Out] =
    new Runnable[F, In, Out]:
      override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out] =
        RunValue(f(input, ctx))

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Leaf(label)

  def fromFunction[F[_]: Applicative, In, Out](f: In => Out): Runnable[F, In, Out] =
    leaf("function")((input, _) => Applicative[F].pure(f(input)))

  def eval[F[_], In, Out](f: (In, RunContext[F]) => F[Out]): Runnable[F, In, Out] =
    leaf("eval")(f)

  def pure[F[_]: Applicative, Out](value: Out): Runnable[F, Any, Out] =
    leaf("pure")((_, _) => Applicative[F].pure(value))

extension [F[_], In, Out](self: Runnable[F, In, Out])
  def describeGraph: RunnableGraph.Node =
    self.graphNode

  def toMermaid: String =
    RunnableGraph.toMermaid(self.graphNode)

extension [F[_]: Functor, In, Out](self: Runnable[F, In, Out])
  def run(input: In, ctx: RunContext[F]): F[Out] =
    self.runValue(input, ctx).toF

extension [F[_]: Monad, In, Out](self: Runnable[F, In, Out])
  def |[Next](next: Runnable[F, Out, Next]): Runnable[F, In, Next] =
    self.andThen(next)

  def >>[Next](next: Runnable[F, Out, Next]): Runnable[F, In, Next] =
    self.andThen(next)

  def >>>[Next](next: Runnable[F, Out, Next]): Runnable[F, In, Next] =
    self.andThen(next)

  def andThen[Next](next: Runnable[F, Out, Next]): Runnable[F, In, Next] =
    new Runnable[F, In, Next]:
      override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Next] =
        RunValue(
          Monad[F].flatMap(self.run(input, ctx))(output => next.run(output, ctx))
        )

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Binary("andThen", self.graphNode, next.graphNode)

  def zip[In0 <: In, Other](right: Runnable[F, In0, Other]): Runnable[F, In0, (Out, Other)] =
    new Runnable[F, In0, (Out, Other)]:
      override def runValue(input: In0, ctx: RunContext[F]): RunValue[F, (Out, Other)] =
        RunValue(
          Monad[F].flatMap(self.run(input, ctx)) { left =>
            Monad[F].map(right.run(input, ctx))(rightValue => (left, rightValue))
          }
        )

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Binary("zip", self.graphNode, right.graphNode)

  def map[Next](f: Out => Next): Runnable[F, In, Next] =
    new Runnable[F, In, Next]:
      override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Next] =
        RunValue(Monad[F].map(self.run(input, ctx))(f))

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Unary("map", self.graphNode)

  def assign: Runnable[F, In, Assigned[In, Out]] =
    new Runnable[F, In, Assigned[In, Out]]:
      override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Assigned[In, Out]] =
        RunValue(Monad[F].map(self.run(input, ctx))(output => Assigned(input, output)))

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Unary("assign", self.graphNode)

  def merge[Next](f: (In, Out) => Next): Runnable[F, In, Next] =
    assign.map(_.merge(f))

extension [F[_], In, Out](self: Runnable[F, In, Out])
  def contramap[Before](f: Before => In): Runnable[F, Before, Out] =
    new Runnable[F, Before, Out]:
      override def runValue(input: Before, ctx: RunContext[F]): RunValue[F, Out] =
        self.runValue(f(input), ctx)

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Unary("contramap", self.graphNode)

  def named(label: String): NamedRunnable[F, In, Out] =
    NamedRunnable(label, self)

extension [F[_]: Monad: Parallel, In, Out](self: Runnable[F, In, Out])
  def par[In0 <: In, Other](right: Runnable[F, In0, Other]): Runnable[F, In0, (Out, Other)] =
    self.zipPar(right)

  def zipPar[In0 <: In, Other](right: Runnable[F, In0, Other]): Runnable[F, In0, (Out, Other)] =
    new Runnable[F, In0, (Out, Other)]:
      override def runValue(input: In0, ctx: RunContext[F]): RunValue[F, (Out, Other)] =
        RunValue((self.run(input, ctx), right.run(input, ctx)).parTupled)

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Binary("zipPar", self.graphNode, right.graphNode)

final case class NamedRunnable[F[_], -In, +Out](
    label: String,
    underlying: Runnable[F, In, Out],
) extends Runnable[F, In, Out]:
  override def runValue(input: In, ctx: RunContext[F]): RunValue[F, Out] =
    underlying.runValue(input, ctx)

  override protected[dsl] val graphNode: RunnableGraph.Node =
    RunnableGraph.Node.Unary(label, underlying.graphNode)
