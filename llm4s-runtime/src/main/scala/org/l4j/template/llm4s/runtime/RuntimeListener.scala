package org.l4j.template.llm4s.runtime

import cats.Applicative
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult

/** Hooks fired by [[AiRuntime]] and [[ToolLoop]] for observability.
  *
  * Implementations are expected to be side-effect-only — return values are
  * discarded. The runtime never inspects success/failure of listener effects
  * (errors are swallowed to keep the chat loop from being broken by a bad
  * tracer). For metrics, logs, or traces, wire a struct that increments a
  * counter or emits a span on each call.
  */
trait RuntimeListener[F[_]]:
  def onChatStarted(request: ChatRequest): F[Unit]
  def onChatCompleted(request: ChatRequest, text: String, turns: Int): F[Unit]
  def onToolCalled(call: ToolCall, context: InvocationContext): F[Unit]
  def onToolSucceeded(call: ToolCall, context: InvocationContext, result: ToolResult): F[Unit]
  def onToolFailed(call: ToolCall, context: InvocationContext, error: Throwable): F[Unit]

object RuntimeListener:

  /** A listener that does nothing — the default. */
  def noop[F[_]](using F: Applicative[F]): RuntimeListener[F] =
    new RuntimeListener[F]:
      override def onChatStarted(request: ChatRequest): F[Unit] = F.unit
      override def onChatCompleted(request: ChatRequest, text: String, turns: Int): F[Unit] = F.unit
      override def onToolCalled(call: ToolCall, context: InvocationContext): F[Unit] = F.unit
      override def onToolSucceeded(call: ToolCall, context: InvocationContext, result: ToolResult): F[Unit] = F.unit
      override def onToolFailed(call: ToolCall, context: InvocationContext, error: Throwable): F[Unit] = F.unit
