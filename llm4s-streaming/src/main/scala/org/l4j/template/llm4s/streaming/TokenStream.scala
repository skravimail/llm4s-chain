package org.l4j.template.llm4s.streaming

import cats.effect.kernel.Concurrent
import fs2.Stream

final case class TokenStream[F[_]](
    events: Stream[F, StreamEvent]
):
  def text: Stream[F, String] =
    events.collect { case StreamEvent.TextDelta(value) => value }

  def collectText(using Concurrent[F]): F[String] =
    text.compile.foldMonoid
