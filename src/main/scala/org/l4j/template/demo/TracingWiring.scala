package org.l4j.template.demo

import cats.Applicative
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.l4j.template.llm4s.agentic.WorkflowListener
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.guardrails.GuardrailListener
import org.l4j.template.llm4s.guardrails.GuardrailViolation
import org.l4j.template.llm4s.openai.HttpListener
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.RuntimeListener
import sttp.model.Method
import sttp.model.Uri

/** Bundle of listeners produced by [[TracingConfig.buildListeners]].
  *
  * Held together so callers can pass the whole tuple into one helper without
  * having to thread four separate values around. */
final case class ListenerBundle[F[_]](
    runtime: RuntimeListener[F],
    http: HttpListener[F],
    guardrails: GuardrailListener[F],
    workflow: WorkflowListener[F],
)

/** Builds the four listener types from a `TracingConfig`, emitting one
  * line per event through `emit`. Off → noop (zero overhead); Info →
  * lifecycle events with timings; Debug → adds payload bodies / full
  * tool args / full results.
  *
  * Lives in the demo package so the multi-module `llm4s-*` libraries
  * stay free of any opinionated formatter. Adopters wire their own
  * listeners (or use [[org.l4j.template.llm4s.tracing.natchez]] for
  * OpenTelemetry-style spans) for production. */
object TracingWiring:

  /** Build a [[ListenerBundle]] for the given config, with each event
    * rendered as one line and passed to `emit`. */
  def buildListeners[F[_]: Applicative](
      config: TracingConfig,
      emit: String => F[Unit],
  ): ListenerBundle[F] =
    ListenerBundle(
      runtime = runtimeListener(config.runtime, emit),
      http = httpListener(config.http, emit),
      guardrails = guardrailListener(config.guardrails, emit),
      workflow = workflowListener(config.workflow, emit),
    )

  // -- formatting helpers --------------------------------------------------

  private val MaxInline = 60
  private val TimeFormat =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  private def trunc(s: String, n: Int = MaxInline): String =
    val flat = s.replace("\n", " ").replace("\r", " ")
    if flat.length <= n then flat else flat.take(n) + "…"

  private def shortId(t: TraceContext): String = t.traceId.value.take(8)
  private def nowStamp: String = TimeFormat.format(Instant.now())

  /** `[13:58:12.123] [trace 12345678] label                  extras` */
  private def fmtTrace(t: TraceContext, label: String, extras: String): String =
    f"[${nowStamp}] [trace ${shortId(t)}] $label%-22s $extras"

  /** `[13:58:12.123] [guardrail     ] label                  extras` — keeps columns aligned
    * with trace-prefixed lines so the output reads cleanly when both
    * are interleaved. */
  private def fmtScope(scope: String, label: String, extras: String): String =
    f"[${nowStamp}] [$scope%-14s] $label%-22s $extras"

  private def ms(nanos: Long): String = f"${nanos / 1_000_000L}%dms"

  // -- runtime listener ----------------------------------------------------

  private def runtimeListener[F[_]: Applicative](
      level: TraceLevel,
      emit: String => F[Unit],
  ): RuntimeListener[F] = level match
    case TraceLevel.Off => RuntimeListener.noop[F]
    case lvl =>
      val verbose = lvl == TraceLevel.Debug
      new RuntimeListener.Default[F]:
        override def onChatStarted(t: TraceContext, r: ChatRequest): F[Unit] =
          val base = s"messages=${r.messages.length} tools=${r.tools.length}"
          val extra =
            if verbose then
              val last = r.messages.lastOption.map(m => s" last=${trunc(m.text)}").getOrElse("")
              base + last
            else base
          emit(fmtTrace(t, "chat.started", extra))

        override def onChatCompleted(
            t: TraceContext, r: ChatRequest, text: String, turns: Int, d: Long,
        ): F[Unit] =
          val body = if verbose then text else trunc(text)
          emit(fmtTrace(t, "chat.completed", s"turns=$turns duration=${ms(d)} text=$body"))

        override def onChatFailed(t: TraceContext, r: ChatRequest, e: Throwable): F[Unit] =
          emit(fmtTrace(t, "chat.failed", s"error=${e.getClass.getSimpleName}: ${e.getMessage}"))

        override def onProviderRequest(t: TraceContext, turn: Int, r: ChatRequest): F[Unit] =
          val base = s"turn=$turn messages=${r.messages.length}"
          val extra =
            if verbose then s"$base last=${r.messages.lastOption.map(m => trunc(m.text)).getOrElse("")}"
            else base
          emit(fmtTrace(t, "provider.request", extra))

        override def onProviderResponse(
            t: TraceContext, turn: Int, r: ChatResponse, d: Long,
        ): F[Unit] =
          val base = s"turn=$turn duration=${ms(d)} toolCalls=${r.message.toolCalls.length}"
          val msgText = r.message.text
          val extra =
            if verbose && msgText.nonEmpty then s"$base text=${trunc(msgText)}"
            else base
          emit(fmtTrace(t, "provider.response", extra))

        override def onToolCalled(c: ToolCall, ctx: InvocationContext): F[Unit] =
          val args = if verbose then c.argumentsJson else trunc(c.argumentsJson)
          emit(fmtTrace(ctx.trace, "tool.called", s"name=${c.name} args=$args"))

        override def onToolSucceeded(
            c: ToolCall, ctx: InvocationContext, r: ToolResult, d: Long,
        ): F[Unit] =
          val res = if verbose then r.text else trunc(r.text)
          emit(fmtTrace(ctx.trace, "tool.succeeded", s"name=${c.name} duration=${ms(d)} result=$res"))

        override def onToolFailed(
            c: ToolCall, ctx: InvocationContext, e: Throwable, attempt: Int, willRetry: Boolean,
        ): F[Unit] =
          emit(fmtTrace(
            ctx.trace, "tool.failed",
            s"name=${c.name} attempt=$attempt willRetry=$willRetry msg=${e.getMessage}",
          ))

        override def onMemoryRead(t: TraceContext, memoryId: String, count: Int): F[Unit] =
          emit(fmtTrace(t, "memory.read", s"id=$memoryId messages=$count"))

        override def onMemoryWritten(t: TraceContext, memoryId: String, count: Int): F[Unit] =
          emit(fmtTrace(t, "memory.written", s"id=$memoryId messages=$count"))

        override def onStreamStarted(t: TraceContext, r: ChatRequest): F[Unit] =
          emit(fmtTrace(t, "stream.started", s"messages=${r.messages.length}"))

        override def onStreamCompleted(
            t: TraceContext, r: ChatRequest, events: Long, d: Long,
        ): F[Unit] =
          emit(fmtTrace(t, "stream.completed", s"events=$events duration=${ms(d)}"))

        override def onStreamFailed(
            t: TraceContext, r: ChatRequest, e: Throwable, events: Long, d: Long,
        ): F[Unit] =
          emit(fmtTrace(
            t, "stream.failed",
            s"events=$events duration=${ms(d)} error=${e.getClass.getSimpleName}: ${e.getMessage}",
          ))

  // -- http listener -------------------------------------------------------

  private def httpListener[F[_]: Applicative](
      level: TraceLevel,
      emit: String => F[Unit],
  ): HttpListener[F] = level match
    case TraceLevel.Off => HttpListener.noop[F]
    case _ =>
      // HttpListener doesn't surface request/response bodies (yet), so
      // Info and Debug currently produce the same output. The constructor
      // accepts both so config remains forward-compatible if the trait
      // grows body events later.
      new HttpListener.Default[F]:
        override def onHttpRequest(t: TraceContext, m: Method, u: Uri): F[Unit] =
          emit(fmtTrace(t, "http.request", s"${m.method} $u"))

        override def onHttpResponse(
            t: TraceContext, m: Method, u: Uri, status: Int, d: Long,
        ): F[Unit] =
          emit(fmtTrace(t, "http.response", s"status=$status duration=${ms(d)}"))

        override def onHttpFailure(
            t: TraceContext, m: Method, u: Uri, e: Throwable, d: Long,
        ): F[Unit] =
          emit(fmtTrace(
            t, "http.failure",
            s"error=${e.getClass.getSimpleName}: ${e.getMessage} duration=${ms(d)}",
          ))

  // -- guardrail listener --------------------------------------------------

  private def guardrailListener[F[_]: Applicative](
      level: TraceLevel,
      emit: String => F[Unit],
  ): GuardrailListener[F] = level match
    case TraceLevel.Off => GuardrailListener.noop[F]
    case lvl =>
      val verbose = lvl == TraceLevel.Debug
      new GuardrailListener.Default[F]:
        override def onInputBlocked(r: ChatRequest, v: GuardrailViolation): F[Unit] =
          val extra =
            if verbose then
              s"code=${v.code} msg=${v.message} messages=${r.messages.length} last=${r.messages.lastOption.map(m => trunc(m.text)).getOrElse("")}"
            else s"code=${v.code} msg=${v.message}"
          emit(fmtScope("guardrail", "input.blocked", extra))

        override def onOutputBlocked(
            r: ChatRequest, p: ChatResponse, v: GuardrailViolation,
        ): F[Unit] =
          val extra =
            if verbose then s"code=${v.code} msg=${v.message} text=${trunc(p.message.text)}"
            else s"code=${v.code} msg=${v.message}"
          emit(fmtScope("guardrail", "output.blocked", extra))

        override def onToolBlocked(
            c: ToolCall, ctx: org.l4j.template.llm4s.core.InvocationContext, v: GuardrailViolation,
        ): F[Unit] =
          val extra =
            if verbose then s"name=${c.name} args=${c.argumentsJson} code=${v.code} msg=${v.message}"
            else s"name=${c.name} code=${v.code} msg=${v.message}"
          emit(fmtTrace(ctx.trace, "tool.blocked", extra))

  // -- workflow listener ---------------------------------------------------

  private def workflowListener[F[_]: Applicative](
      level: TraceLevel,
      emit: String => F[Unit],
  ): WorkflowListener[F] = level match
    case TraceLevel.Off => WorkflowListener.noop[F]
    case lvl =>
      val verbose = lvl == TraceLevel.Debug
      new WorkflowListener.Default[F]:
        override def onAgentStarted(name: String, input: Any): F[Unit] =
          val extra = if verbose then s"name=$name input=${trunc(String.valueOf(input))}" else s"name=$name"
          emit(fmtScope("workflow", "agent.started", extra))

        override def onAgentSucceeded(name: String, output: Any, d: Long): F[Unit] =
          val extra =
            if verbose then s"name=$name duration=${ms(d)} output=${trunc(String.valueOf(output))}"
            else s"name=$name duration=${ms(d)}"
          emit(fmtScope("workflow", "agent.succeeded", extra))

        override def onAgentFailed(name: String, e: Throwable, d: Long): F[Unit] =
          emit(fmtScope(
            "workflow", "agent.failed",
            s"name=$name duration=${ms(d)} error=${e.getClass.getSimpleName}: ${e.getMessage}",
          ))
