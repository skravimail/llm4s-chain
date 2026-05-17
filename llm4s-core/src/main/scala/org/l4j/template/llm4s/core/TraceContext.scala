package org.l4j.template.llm4s.core

import java.util.UUID

/** Globally-unique identifier for one logical operation (a chat invocation).
  * Stays constant across every listener event fired during that invocation —
  * tool calls, provider round-trips, memory reads/writes — so adopters can
  * tie related events back to the same conversation when correlating logs,
  * metrics, or traces. */
opaque type TraceId = String
object TraceId:
  def apply(value: String): TraceId = value
  def random(): TraceId = UUID.randomUUID().toString
  extension (id: TraceId) def value: String = id

/** Identifier for a single span within a trace (one tool call, one provider
  * round-trip). Spans form a tree rooted at the chat-level span; the parent
  * of each child is on `TraceContext.parentSpanId`. */
opaque type SpanId = String
object SpanId:
  def apply(value: String): SpanId = value
  def random(): SpanId = UUID.randomUUID().toString.take(8)
  extension (id: SpanId) def value: String = id

/** Trace-tree node. Carried alongside [[InvocationContext]] and passed to
  * every chat-level listener event so adopters can:
  *
  *   1. correlate concurrent chats (different `traceId`s);
  *   2. nest individual tool / provider calls under the parent chat span
  *      (`parentSpanId` of a tool-call span is the chat's `spanId`);
  *   3. emit OpenTelemetry / Natchez spans without re-inventing IDs.
  *
  * Generated via `TraceContext.fresh()` at the start of each `AiRuntime.run`.
  * Tests can pin the value with `TraceContext.of(...)` for determinism.
  */
final case class TraceContext(
    traceId: TraceId,
    spanId: SpanId,
    parentSpanId: Option[SpanId] = None,
):
  /** Open a child span beneath this one. Used for tool calls and provider
    * round-trips so each carries a fresh `spanId` while inheriting the same
    * `traceId`. */
  def child(): TraceContext =
    TraceContext(traceId, SpanId.random(), Some(spanId))

object TraceContext:
  /** A fresh root context. Side-effecting (UUIDs); intended to be called at
    * the start of each chat invocation, not on every event. */
  def fresh(): TraceContext = TraceContext(TraceId.random(), SpanId.random())

  /** Deterministic constructor for tests. */
  def of(traceId: String, spanId: String, parentSpanId: Option[String] = None): TraceContext =
    TraceContext(TraceId(traceId), SpanId(spanId), parentSpanId.map(SpanId(_)))
