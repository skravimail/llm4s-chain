package org.l4j.template.llm4s.guardrails

import cats.Applicative
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.InvocationContext
import org.l4j.template.llm4s.core.ToolCall

/** Observability hook for the guardrail layer.
  *
  * Kept distinct from `RuntimeListener` because guardrails are a layer
  * *around* the runtime (often a guarded backend wrapping a real backend)
  * rather than inside it. Adopters can wire one or both depending on what
  * they care about; the noop default means an unwired chain has zero
  * overhead.
  *
  * All hooks default to noop (via `Default`) so partial implementations
  * stay compatible across new event additions.
  */
trait GuardrailListener[F[_]]:
  /** Fired when an input guardrail returns `Block(violation)`. The original
    * (un-modified) request is passed because the chain may have transformed
    * it in sequential mode before the block fired. */
  def onInputBlocked(request: ChatRequest, violation: GuardrailViolation): F[Unit]

  /** Fired when an output guardrail returns `Block(violation)`. */
  def onOutputBlocked(
      request: ChatRequest,
      response: ChatResponse,
      violation: GuardrailViolation,
  ): F[Unit]

  /** Fired when a tool guardrail returns `Block(violation)`. */
  def onToolBlocked(
      call: ToolCall,
      context: InvocationContext,
      violation: GuardrailViolation,
  ): F[Unit]

object GuardrailListener:
  def noop[F[_]](using F: Applicative[F]): GuardrailListener[F] = new GuardrailListener[F]:
    override def onInputBlocked(r: ChatRequest, v: GuardrailViolation): F[Unit] = F.unit
    override def onOutputBlocked(r: ChatRequest, p: ChatResponse, v: GuardrailViolation): F[Unit] = F.unit
    override def onToolBlocked(c: ToolCall, ctx: InvocationContext, v: GuardrailViolation): F[Unit] = F.unit

  abstract class Default[F[_]](using F: Applicative[F]) extends GuardrailListener[F]:
    override def onInputBlocked(r: ChatRequest, v: GuardrailViolation): F[Unit] = F.unit
    override def onOutputBlocked(r: ChatRequest, p: ChatResponse, v: GuardrailViolation): F[Unit] = F.unit
    override def onToolBlocked(c: ToolCall, ctx: InvocationContext, v: GuardrailViolation): F[Unit] = F.unit
