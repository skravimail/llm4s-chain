package org.l4j.template.llm4s.openai

import cats.effect.kernel.Sync
import cats.syntax.all.*
import sttp.capabilities.Effect
import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.SttpBackend
import sttp.monad.MonadError as SttpMonadError

/** Wraps any `SttpBackend[F, P]` to emit `HttpListener` events around each
  * `send`. Use this to add HTTP-layer tracing to `OpenAiCompatBackend`
  * without touching the rest of the wiring:
  *
  * {{{
  * AsyncHttpClientCatsBackend.resource[IO]().map { raw =>
  *   val traced = TracedSttpBackend[IO, Any](raw, myHttpListener)
  *   OpenAiCompatBackend.fromSttp[IO](config, traced)
  * }
  * }}}
  *
  * Failures are forwarded with `onHttpFailure` and re-raised; successes
  * report status code and duration via `onHttpResponse`.
  */
final class TracedSttpBackend[F[_]: Sync, P](
    underlying: SttpBackend[F, P],
    listener: HttpListener[F],
) extends SttpBackend[F, P]:

  override def send[T, R >: P & Effect[F]](request: Request[T, R]): F[Response[T]] =
    for
      _ <- listener.onHttpRequest(request.method, request.uri)
      startNs <- Sync[F].delay(System.nanoTime())
      attempted <- underlying.send(request).attempt
      durationNs = System.nanoTime() - startNs
      response <- attempted match
        case Right(resp) =>
          listener.onHttpResponse(request.method, request.uri, resp.code.code, durationNs)
            .as(resp)
        case Left(err) =>
          listener.onHttpFailure(request.method, request.uri, err, durationNs) >>
            Sync[F].raiseError(err)
    yield response

  override def close(): F[Unit] = underlying.close()

  override val responseMonad: SttpMonadError[F] = underlying.responseMonad

object TracedSttpBackend:
  def apply[F[_]: Sync, P](
      underlying: SttpBackend[F, P],
      listener: HttpListener[F],
  ): TracedSttpBackend[F, P] =
    new TracedSttpBackend[F, P](underlying, listener)
