package org.l4j.template.llm4s.runtime

import cats.Applicative
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext

/** Observability hooks fired by [[AiRuntime]] and [[ToolLoop]].
  *
  * **Listener v2 (PR-8b)** evolves the original PR-8 hook surface to give
  * adopters the seams they need for true end-to-end tracing:
  *
  *   - **Correlation**: every event carries a [[TraceContext]] so concurrent
  *     chats and their nested tool / provider calls can be tied together
  *     without parsing log lines.
  *   - **Timing**: completion events carry a duration in nanoseconds so
  *     adopters don't have to wrap the listener themselves to compute p99.
  *   - **Provider visibility**: per‑backend round-trip events surface the
  *     N individual `backend.chat` calls inside a multi-turn loop (the v1
  *     hooks only bracketed the whole loop).
  *   - **Retry visibility**: tool-failure events carry `attempt` and
  *     `willRetry` so a `RetryOnce` retry doesn't look like two unrelated
  *     failures.
  *   - **Memory I/O**: separate hooks let an adopter observe transcript
  *     reads/writes via [[org.l4j.template.llm4s.memory.MemoryAwareRuntime]]
  *     without inserting a custom `ChatMemory` decorator.
  *
  * All methods have a no-op default, so:
  *   - existing PR-8 listeners that override only a subset of methods keep
  *     working without changes;
  *   - new event surfaces can be added in future PRs without breaking
  *     adopters.
  *
  * The runtime never inspects listener return values and never propagates
  * listener errors — a buggy tracer must not break the chat loop. For
  * metrics, logs, or traces, increment a counter or open a span on each
  * call.
  */
trait RuntimeListener[F[_]]:

  // -- Chat scope ------------------------------------------------------------

  def onChatStarted(trace: TraceContext, request: ChatRequest): F[Unit]

  def onChatCompleted(
      trace: TraceContext,
      request: ChatRequest,
      text: String,
      turns: Int,
      durationNanos: Long,
  ): F[Unit]

  /** Fired when the chat loop aborts via a typed `AiRuntimeError`
    * (`MaxTurnsExceeded`, `ContentFiltered`, `ProviderError`, …). */
  def onChatFailed(trace: TraceContext, request: ChatRequest, error: Throwable): F[Unit]

  // -- Per-provider-call scope ----------------------------------------------

  /** Fired once per `backend.chat(...)` call inside the chat loop. Useful
    * for per-turn provider latency / token-count metrics. */
  def onProviderRequest(trace: TraceContext, turn: Int, request: ChatRequest): F[Unit]

  def onProviderResponse(
      trace: TraceContext,
      turn: Int,
      response: ChatResponse,
      durationNanos: Long,
  ): F[Unit]

  // -- Tool scope -----------------------------------------------------------

  def onToolCalled(call: ToolCall, context: InvocationContext): F[Unit]

  def onToolSucceeded(
      call: ToolCall,
      context: InvocationContext,
      result: ToolResult,
      durationNanos: Long,
  ): F[Unit]

  /** Fired on every executor failure attempt. `attempt` is 1-based;
    * `willRetry = true` means a `RetryOnce` policy will re-invoke the
    * executor immediately after this event. */
  def onToolFailed(
      call: ToolCall,
      context: InvocationContext,
      error: Throwable,
      attempt: Int,
      willRetry: Boolean,
  ): F[Unit]

  // -- Memory scope ---------------------------------------------------------

  def onMemoryRead(trace: TraceContext, memoryId: String, messageCount: Int): F[Unit]
  def onMemoryWritten(trace: TraceContext, memoryId: String, messageCount: Int): F[Unit]

  // -- Streaming scope ------------------------------------------------------

  /** Fired when a `StreamingAiRuntime.stream(...)` consumer subscribes (i.e.
    * the first event is pulled, not when the `TokenStream` is constructed —
    * fs2 is lazy). Stream events are too chatty to emit individually; the
    * pair below brackets the whole stream and reports total event count. */
  def onStreamStarted(trace: TraceContext, request: ChatRequest): F[Unit]

  def onStreamCompleted(
      trace: TraceContext,
      request: ChatRequest,
      eventCount: Long,
      durationNanos: Long,
  ): F[Unit]

  def onStreamFailed(
      trace: TraceContext,
      request: ChatRequest,
      error: Throwable,
      eventCount: Long,
      durationNanos: Long,
  ): F[Unit]

object RuntimeListener:

  /** All-noop listener. Provided as the default so unwired runtimes carry no
    * observability overhead. */
  def noop[F[_]](using F: Applicative[F]): RuntimeListener[F] = new RuntimeListener[F]:
    override def onChatStarted(trace: TraceContext, request: ChatRequest): F[Unit] = F.unit
    override def onChatCompleted(t: TraceContext, r: ChatRequest, x: String, n: Int, d: Long): F[Unit] = F.unit
    override def onChatFailed(t: TraceContext, r: ChatRequest, e: Throwable): F[Unit] = F.unit
    override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): F[Unit] = F.unit
    override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): F[Unit] = F.unit
    override def onToolCalled(c: ToolCall, ctx: InvocationContext): F[Unit] = F.unit
    override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long): F[Unit] = F.unit
    override def onToolFailed(c: ToolCall, ctx: InvocationContext, e: Throwable, a: Int, w: Boolean): F[Unit] = F.unit
    override def onMemoryRead(t: TraceContext, id: String, n: Int): F[Unit] = F.unit
    override def onMemoryWritten(t: TraceContext, id: String, n: Int): F[Unit] = F.unit
    override def onStreamStarted(t: TraceContext, r: ChatRequest): F[Unit] = F.unit
    override def onStreamCompleted(t: TraceContext, r: ChatRequest, n: Long, d: Long): F[Unit] = F.unit
    override def onStreamFailed(t: TraceContext, r: ChatRequest, e: Throwable, n: Long, d: Long): F[Unit] = F.unit

  /** Helper for adopters who want a partial-override style with reasonable
    * defaults. Extend this instead of the bare trait and only override the
    * events you care about. */
  abstract class Default[F[_]](using F: Applicative[F]) extends RuntimeListener[F]:
    override def onChatStarted(trace: TraceContext, request: ChatRequest): F[Unit] = F.unit
    override def onChatCompleted(t: TraceContext, r: ChatRequest, x: String, n: Int, d: Long): F[Unit] = F.unit
    override def onChatFailed(t: TraceContext, r: ChatRequest, e: Throwable): F[Unit] = F.unit
    override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): F[Unit] = F.unit
    override def onProviderResponse(t: TraceContext, turn: Int, r: ChatResponse, d: Long): F[Unit] = F.unit
    override def onToolCalled(c: ToolCall, ctx: InvocationContext): F[Unit] = F.unit
    override def onToolSucceeded(c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long): F[Unit] = F.unit
    override def onToolFailed(c: ToolCall, ctx: InvocationContext, e: Throwable, a: Int, w: Boolean): F[Unit] = F.unit
    override def onMemoryRead(t: TraceContext, id: String, n: Int): F[Unit] = F.unit
    override def onMemoryWritten(t: TraceContext, id: String, n: Int): F[Unit] = F.unit
    override def onStreamStarted(t: TraceContext, r: ChatRequest): F[Unit] = F.unit
    override def onStreamCompleted(t: TraceContext, r: ChatRequest, n: Long, d: Long): F[Unit] = F.unit
    override def onStreamFailed(t: TraceContext, r: ChatRequest, e: Throwable, n: Long, d: Long): F[Unit] = F.unit
