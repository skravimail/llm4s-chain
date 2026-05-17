package org.l4j.template.llm4s.tracing.natchez

import cats.Monad
import natchez.Trace
import natchez.TraceValue.StringValue
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.InvocationContext
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.guardrails.GuardrailListener
import org.l4j.template.llm4s.guardrails.GuardrailViolation

/** Natchez adapter for [[GuardrailListener]]. Mirrors the design of
  * [[NatchezRuntimeListener]]: attaches fields to the ambient span rather
  * than opening new spans, because the listener doesn't bracket effects. */
final class NatchezGuardrailListener[F[_]: Monad: Trace] extends GuardrailListener[F]:

  override def onInputBlocked(request: ChatRequest, violation: GuardrailViolation): F[Unit] =
    Trace[F].put(
      "ai.event"             -> StringValue("guardrail.input.blocked"),
      "ai.guardrail.code"    -> StringValue(violation.code),
      "ai.guardrail.message" -> StringValue(violation.message),
    )

  override def onOutputBlocked(
      request: ChatRequest,
      response: ChatResponse,
      violation: GuardrailViolation,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"             -> StringValue("guardrail.output.blocked"),
      "ai.guardrail.code"    -> StringValue(violation.code),
      "ai.guardrail.message" -> StringValue(violation.message),
    )

  override def onToolBlocked(
      call: ToolCall,
      context: InvocationContext,
      violation: GuardrailViolation,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"             -> StringValue("guardrail.tool.blocked"),
      "ai.tool.name"         -> StringValue(call.name),
      "ai.guardrail.code"    -> StringValue(violation.code),
      "ai.guardrail.message" -> StringValue(violation.message),
    )

object NatchezGuardrailListener:
  def apply[F[_]: Monad: Trace]: NatchezGuardrailListener[F] =
    new NatchezGuardrailListener[F]
