package org.l4j.template.llm4s.core

/** A cross-cutting value passed to every tool executor and tool guardrail.
  *
  * Lives in `llm4s-core` rather than `llm4s-runtime` because both `llm4s-tools`
  * and `llm4s-guardrails` reference it without needing the rest of the
  * runtime — keeping it here lets `llm4s-guardrails` describe its `Tool*`
  * trait in terms of core types only.
  */
final case class InvocationContext(
    turn: Int,
    request: ChatRequest,
    toolCall: ToolCall,
)
