package org.l4j.template.llm4s.openai

import cats.Applicative
import sttp.model.Method
import sttp.model.Uri

/** Observability hook for HTTP-level events in the sttp transport.
  *
  * Kept distinct from `RuntimeListener` (chat scope) because HTTP calls
  * happen *inside* a single backend.chat invocation — a single chat fires
  * one chat-event but N HTTP-events (one per provider round-trip). A
  * separate trait keeps both surfaces tightly scoped.
  *
  * Wire one by wrapping any `SttpBackend` with [[TracedSttpBackend]] and
  * passing the listener.
  *
  * No `TraceContext` parameter here: the runtime doesn't yet thread its
  * `TraceContext` down to the transport layer (would require changing the
  * `OpenAiTransport` interface). Adopters who want HTTP events correlated
  * with chat events should use `NatchezHttpListener` together with
  * natchez's `IOLocal`-based `Trace`, which auto-propagates the ambient
  * span — see PR-8e in CODE_REVIEW.md.
  */
trait HttpListener[F[_]]:
  def onHttpRequest(method: Method, uri: Uri): F[Unit]

  def onHttpResponse(
      method: Method,
      uri: Uri,
      statusCode: Int,
      durationNanos: Long,
  ): F[Unit]

  def onHttpFailure(
      method: Method,
      uri: Uri,
      error: Throwable,
      durationNanos: Long,
  ): F[Unit]

object HttpListener:
  def noop[F[_]](using F: Applicative[F]): HttpListener[F] = new HttpListener[F]:
    override def onHttpRequest(m: Method, u: Uri): F[Unit] = F.unit
    override def onHttpResponse(m: Method, u: Uri, s: Int, d: Long): F[Unit] = F.unit
    override def onHttpFailure(m: Method, u: Uri, e: Throwable, d: Long): F[Unit] = F.unit

  abstract class Default[F[_]](using F: Applicative[F]) extends HttpListener[F]:
    override def onHttpRequest(m: Method, u: Uri): F[Unit] = F.unit
    override def onHttpResponse(m: Method, u: Uri, s: Int, d: Long): F[Unit] = F.unit
    override def onHttpFailure(m: Method, u: Uri, e: Throwable, d: Long): F[Unit] = F.unit
