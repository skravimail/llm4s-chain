package org.l4j.template.llm4s.tracing.natchez

import cats.Monad
import cats.syntax.all.*
import natchez.Trace
import natchez.TraceValue.{NumberValue, StringValue}
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeListener

/** Natchez adapter for [[RuntimeListener]].
  *
  * Bridges our point-in-time events onto whatever natchez span is already
  * active in the calling effect. The expected usage is:
  *
  * {{{
  * Trace[F].span("ai.chat") {
  *   AiRuntime[F](backend, config, NatchezRuntimeListener[F]).chat(...)
  * }
  * }}}
  *
  * On each event we attach fields to the current span via `Trace[F].put`,
  * mapping our `TraceContext.traceId` into `ai.trace.id` so adopters can
  * correlate the natchez span with the framework's own internal trace id.
  * We do NOT open new child spans per tool call / per provider request:
  * span lifecycles in natchez are scoped via Resource.use, which doesn't
  * fit a listener seam. Adopters who want a nested span tree should wrap
  * the relevant operations in `Trace[F].span(...)` themselves; the listener
  * still augments them with attributes.
  */
final class NatchezRuntimeListener[F[_]: Monad: Trace] extends RuntimeListener[F]:

  override def spanChat[A](trace: TraceContext, request: ChatRequest)(use: F[A]): F[A] =
    Trace[F].span("ai.chat")(use)

  override def spanProviderCall[A](trace: TraceContext, turn: Int, request: ChatRequest)(use: F[A]): F[A] =
    Trace[F].span("ai.provider.request")(use)

  override def spanToolCall[A](call: ToolCall, context: InvocationContext)(use: F[A]): F[A] =
    Trace[F].span(s"ai.tool.${call.name}")(use)

  override def onChatStarted(trace: TraceContext, request: ChatRequest): F[Unit] =
    Trace[F].put(
      "ai.event"            -> StringValue("chat.started"),
      "ai.trace.id"         -> StringValue(trace.traceId.value),
      "ai.span.id"          -> StringValue(trace.spanId.value),
      "ai.chat.messages"    -> NumberValue(request.messages.length),
      "ai.chat.tools"       -> NumberValue(request.tools.length),
    )

  override def onChatCompleted(
      trace: TraceContext,
      request: ChatRequest,
      text: String,
      turns: Int,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"            -> StringValue("chat.completed"),
      "ai.trace.id"         -> StringValue(trace.traceId.value),
      "ai.chat.turns"       -> NumberValue(turns),
      "ai.chat.text.length" -> NumberValue(text.length),
      "ai.duration.ns"      -> NumberValue(durationNanos),
    )

  override def onChatFailed(
      trace: TraceContext,
      request: ChatRequest,
      error: Throwable,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"        -> StringValue("chat.failed"),
      "ai.trace.id"     -> StringValue(trace.traceId.value),
      "ai.error.class"  -> StringValue(error.getClass.getName),
      "ai.error.message" -> StringValue(Option(error.getMessage).getOrElse("")),
    ) >> Trace[F].attachError(error)

  override def onProviderRequest(
      trace: TraceContext,
      turn: Int,
      request: ChatRequest,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"           -> StringValue("provider.request"),
      "ai.trace.id"        -> StringValue(trace.traceId.value),
      "ai.provider.turn"   -> NumberValue(turn),
      "ai.provider.tools"  -> NumberValue(request.tools.length),
    )

  override def onProviderResponse(
      trace: TraceContext,
      turn: Int,
      response: ChatResponse,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"             -> StringValue("provider.response"),
      "ai.trace.id"          -> StringValue(trace.traceId.value),
      "ai.provider.turn"     -> NumberValue(turn),
      "ai.provider.tokens"   -> NumberValue(response.usage.map(_.totalTokens).getOrElse(0)),
      "ai.duration.ns"       -> NumberValue(durationNanos),
    )

  override def onToolCalled(call: ToolCall, context: InvocationContext): F[Unit] =
    Trace[F].put(
      "ai.event"          -> StringValue("tool.called"),
      "ai.trace.id"       -> StringValue(context.trace.traceId.value),
      "ai.tool.name"      -> StringValue(call.name),
      "ai.tool.call_id"   -> StringValue(call.callId.getOrElse("")),
    )

  override def onToolSucceeded(
      call: ToolCall,
      context: InvocationContext,
      result: ToolResult,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"           -> StringValue("tool.succeeded"),
      "ai.trace.id"        -> StringValue(context.trace.traceId.value),
      "ai.tool.name"       -> StringValue(call.name),
      "ai.duration.ns"     -> NumberValue(durationNanos),
    )

  override def onToolFailed(
      call: ToolCall,
      context: InvocationContext,
      error: Throwable,
      attempt: Int,
      willRetry: Boolean,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"           -> StringValue("tool.failed"),
      "ai.trace.id"        -> StringValue(context.trace.traceId.value),
      "ai.tool.name"       -> StringValue(call.name),
      "ai.tool.attempt"    -> NumberValue(attempt),
      "ai.tool.will_retry" -> StringValue(willRetry.toString),
      "ai.error.class"     -> StringValue(error.getClass.getName),
      "ai.error.message"   -> StringValue(Option(error.getMessage).getOrElse("")),
    ) >> Trace[F].attachError(error)

  override def onMemoryRead(trace: TraceContext, memoryId: String, messageCount: Int): F[Unit] =
    Trace[F].put(
      "ai.event"        -> StringValue("memory.read"),
      "ai.trace.id"     -> StringValue(trace.traceId.value),
      "ai.memory.id"    -> StringValue(memoryId),
      "ai.memory.count" -> NumberValue(messageCount),
    )

  override def onMemoryWritten(trace: TraceContext, memoryId: String, messageCount: Int): F[Unit] =
    Trace[F].put(
      "ai.event"        -> StringValue("memory.written"),
      "ai.trace.id"     -> StringValue(trace.traceId.value),
      "ai.memory.id"    -> StringValue(memoryId),
      "ai.memory.count" -> NumberValue(messageCount),
    )

  override def onStreamStarted(trace: TraceContext, request: ChatRequest): F[Unit] =
    Trace[F].put(
      "ai.event"     -> StringValue("stream.started"),
      "ai.trace.id"  -> StringValue(trace.traceId.value),
    )

  override def onStreamCompleted(
      trace: TraceContext,
      request: ChatRequest,
      eventCount: Long,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"          -> StringValue("stream.completed"),
      "ai.trace.id"       -> StringValue(trace.traceId.value),
      "ai.stream.events"  -> NumberValue(eventCount),
      "ai.duration.ns"    -> NumberValue(durationNanos),
    )

  override def onStreamFailed(
      trace: TraceContext,
      request: ChatRequest,
      error: Throwable,
      eventCount: Long,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"          -> StringValue("stream.failed"),
      "ai.trace.id"       -> StringValue(trace.traceId.value),
      "ai.stream.events"  -> NumberValue(eventCount),
      "ai.duration.ns"    -> NumberValue(durationNanos),
      "ai.error.class"    -> StringValue(error.getClass.getName),
      "ai.error.message"  -> StringValue(Option(error.getMessage).getOrElse("")),
    ) >> Trace[F].attachError(error)

object NatchezRuntimeListener:
  def apply[F[_]: Monad: Trace]: NatchezRuntimeListener[F] =
    new NatchezRuntimeListener[F]
