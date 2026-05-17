package org.l4j.template.llm4s.guardrails

import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.InvocationContext

trait InputGuardrail[F[_]]:
  def check(request: ChatRequest): F[GuardrailResult[ChatRequest]]

trait OutputGuardrail[F[_]]:
  def check(request: ChatRequest, response: ChatResponse): F[GuardrailResult[ChatResponse]]

trait ToolGuardrail[F[_]]:
  def check(call: ToolCall, context: InvocationContext): F[GuardrailResult[ToolCall]]

