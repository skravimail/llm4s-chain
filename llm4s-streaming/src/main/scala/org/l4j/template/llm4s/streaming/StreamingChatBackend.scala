package org.l4j.template.llm4s.streaming

import fs2.Stream
import org.l4j.template.llm4s.core.ChatRequest

trait StreamingChatBackend[F[_]]:
  def stream(request: ChatRequest): Stream[F, StreamEvent]
