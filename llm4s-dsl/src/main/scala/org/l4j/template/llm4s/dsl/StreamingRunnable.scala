package org.l4j.template.llm4s.dsl

import fs2.Stream

trait StreamingRunnable[F[_], -In, +Out]:
  def stream(input: In, ctx: RunContext[F]): Stream[F, Out]
  protected[dsl] def graphNode: RunnableGraph.Node

object StreamingRunnable:
  def leaf[F[_], In, Out](
      label: String
  )(f: (In, RunContext[F]) => Stream[F, Out]): StreamingRunnable[F, In, Out] =
    new StreamingRunnable[F, In, Out]:
      override def stream(input: In, ctx: RunContext[F]): Stream[F, Out] =
        f(input, ctx)

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Leaf(label)

extension [F[_], In, Out](self: StreamingRunnable[F, In, Out])
  def describeStreamingGraph: RunnableGraph.Node =
    self.graphNode

  def toStreamingMermaid: String =
    RunnableGraph.toMermaid(self.graphNode)

extension [F[_], In, Mid](self: Runnable[F, In, Mid])
  def andThenStream[Out](next: StreamingRunnable[F, Mid, Out])(using cats.Functor[F]): StreamingRunnable[F, In, Out] =
    new StreamingRunnable[F, In, Out]:
      override def stream(input: In, ctx: RunContext[F]): Stream[F, Out] =
        Stream.eval(self.run(input, ctx)).flatMap(next.stream(_, ctx))

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Binary("andThenStream", self.describeGraph, next.describeStreamingGraph)

extension [F[_], In, Out](self: StreamingRunnable[F, In, Out])
  def mapStream[Next](f: Out => Next): StreamingRunnable[F, In, Next] =
    new StreamingRunnable[F, In, Next]:
      override def stream(input: In, ctx: RunContext[F]): Stream[F, Next] =
        self.stream(input, ctx).map(f)

      override protected[dsl] val graphNode: RunnableGraph.Node =
        RunnableGraph.Node.Unary("mapStream", self.describeStreamingGraph)
