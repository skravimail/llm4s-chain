package org.l4j.template.llm4s.streaming

trait StreamSubscriber[F[_]]:
  def onEvent(event: StreamEvent): F[Unit]
