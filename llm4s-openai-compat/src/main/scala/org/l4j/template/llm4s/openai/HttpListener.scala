package org.l4j.template.llm4s.openai

import cats.Applicative
import org.l4j.template.llm4s.core.TraceContext
import sttp.model.Method
import sttp.model.Uri

/** Observability hook for HTTP-level events in the sttp transport.
  *
  * Kept distinct from `RuntimeListener` (chat scope) because HTTP calls
  * happen *inside* a single backend.chat invocation — a single chat fires
  * one chat-event but N HTTP-events (one per provider round-trip). A
  * separate trait keeps both surfaces tightly scoped.
  *
  * Each event carries the `TraceContext` of the originating chat (PR-8f),
  * so HTTP events correlate with chat / tool events without needing the
  * IOLocal-based natchez Trace. When the trace isn't known
  * (e.g. `TracedSttpBackend` wrapping a non-OpenAI backend), the listener
  * is invoked with a freshly-generated context.
  */
trait HttpListener[F[_]]:
  def onHttpRequest(trace: TraceContext, method: Method, uri: Uri): F[Unit]

  def onHttpResponse(
      trace: TraceContext,
      method: Method,
      uri: Uri,
      statusCode: Int,
      durationNanos: Long,
  ): F[Unit]

  def onHttpFailure(
      trace: TraceContext,
      method: Method,
      uri: Uri,
      error: Throwable,
      durationNanos: Long,
  ): F[Unit]

object HttpListener:
  def noop[F[_]](using F: Applicative[F]): HttpListener[F] = new HttpListener[F]:
    override def onHttpRequest(t: TraceContext, m: Method, u: Uri): F[Unit] = F.unit
    override def onHttpResponse(t: TraceContext, m: Method, u: Uri, s: Int, d: Long): F[Unit] = F.unit
    override def onHttpFailure(t: TraceContext, m: Method, u: Uri, e: Throwable, d: Long): F[Unit] = F.unit

  abstract class Default[F[_]](using F: Applicative[F]) extends HttpListener[F]:
    override def onHttpRequest(t: TraceContext, m: Method, u: Uri): F[Unit] = F.unit
    override def onHttpResponse(t: TraceContext, m: Method, u: Uri, s: Int, d: Long): F[Unit] = F.unit
    override def onHttpFailure(t: TraceContext, m: Method, u: Uri, e: Throwable, d: Long): F[Unit] = F.unit
