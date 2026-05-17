package org.l4j.template.llm4s.core

/** A cross-cutting value passed to every tool executor and tool guardrail.
  *
  * Lives in `llm4s-core` rather than `llm4s-runtime` because both `llm4s-tools`
  * and `llm4s-guardrails` reference it without needing the rest of the
  * runtime — keeping it here lets `llm4s-guardrails` describe its `Tool*`
  * trait in terms of core types only.
  *
  * `trace` was added in PR-8b so any listener event (or tool implementation)
  * can correlate itself to the parent chat invocation. Defaults to a fresh
  * context so existing callers that build `InvocationContext` by hand keep
  * compiling.
  */
final case class InvocationContext(
    turn: Int,
    request: ChatRequest,
    toolCall: ToolCall,
    trace: TraceContext = TraceContext.fresh(),
)
