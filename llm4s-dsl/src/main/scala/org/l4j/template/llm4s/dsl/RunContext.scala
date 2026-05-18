package org.l4j.template.llm4s.dsl

import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener

trait RunContext[F[_]]:
  def backend: ChatBackend[F]
  def runtimeConfig: RuntimeConfig
  def runtimeListener: RuntimeListener[F]
  def traceContext: TraceContext

object RunContext:
  def apply[F[_]](
      backend0: ChatBackend[F],
      runtimeConfig0: RuntimeConfig,
      runtimeListener0: RuntimeListener[F],
      traceContext0: TraceContext = TraceContext.fresh(),
  ): RunContext[F] =
    DefaultRunContext(
      backend = backend0,
      runtimeConfig = runtimeConfig0,
      runtimeListener = runtimeListener0,
      traceContext = traceContext0,
    )

final case class DefaultRunContext[F[_]](
    backend: ChatBackend[F],
    runtimeConfig: RuntimeConfig,
    runtimeListener: RuntimeListener[F],
    traceContext: TraceContext,
) extends RunContext[F]
