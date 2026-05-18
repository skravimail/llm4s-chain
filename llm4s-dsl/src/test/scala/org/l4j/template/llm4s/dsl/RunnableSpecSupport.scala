package org.l4j.template.llm4s.dsl

import cats.effect.IO
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener

object RunnableSpecSupport:
  val stubContext: RunContext[IO] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.raiseError(RuntimeException(s"unexpected backend call: ${request.messages.length}"))

    RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = RuntimeListener.noop[IO],
      traceContext0 = TraceContext.fresh(),
    )
