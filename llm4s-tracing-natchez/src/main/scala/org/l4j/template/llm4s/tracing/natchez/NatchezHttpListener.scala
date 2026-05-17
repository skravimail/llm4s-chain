package org.l4j.template.llm4s.tracing.natchez

import cats.Monad
import cats.syntax.all.*
import natchez.Trace
import natchez.TraceValue.{NumberValue, StringValue}
import org.l4j.template.llm4s.openai.HttpListener
import sttp.model.Method
import sttp.model.Uri

/** Natchez adapter for the sttp HTTP listener (PR-8e).
  *
  * With natchez's `IOLocal`-backed `Trace`, the ambient span at HTTP send
  * time is the same span the calling chat opened — so HTTP events attach
  * to the chat's natchez span automatically. No explicit `TraceContext`
  * threading needed at this layer.
  */
final class NatchezHttpListener[F[_]: Monad: Trace] extends HttpListener[F]:

  override def onHttpRequest(method: Method, uri: Uri): F[Unit] =
    Trace[F].put(
      "ai.event"       -> StringValue("http.request"),
      "http.method"    -> StringValue(method.method),
      "http.url"       -> StringValue(uri.toString),
    )

  override def onHttpResponse(
      method: Method,
      uri: Uri,
      statusCode: Int,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"           -> StringValue("http.response"),
      "http.method"        -> StringValue(method.method),
      "http.url"           -> StringValue(uri.toString),
      "http.status_code"   -> NumberValue(statusCode),
      "ai.duration.ns"     -> NumberValue(durationNanos),
    )

  override def onHttpFailure(
      method: Method,
      uri: Uri,
      error: Throwable,
      durationNanos: Long,
  ): F[Unit] =
    Trace[F].put(
      "ai.event"          -> StringValue("http.failure"),
      "http.method"       -> StringValue(method.method),
      "http.url"          -> StringValue(uri.toString),
      "ai.duration.ns"    -> NumberValue(durationNanos),
      "ai.error.class"    -> StringValue(error.getClass.getName),
      "ai.error.message"  -> StringValue(Option(error.getMessage).getOrElse("")),
    ) >> Trace[F].attachError(error)

object NatchezHttpListener:
  def apply[F[_]: Monad: Trace]: NatchezHttpListener[F] =
    new NatchezHttpListener[F]
