package org.l4j.template.llm4s.openai

import cats.effect.Async
import cats.effect.Resource
import cats.effect.kernel.Deferred
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream
import org.asynchttpclient.AsyncHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.HttpResponseBodyPart
import org.asynchttpclient.HttpResponseStatus
import org.l4j.template.llm4s.core.TraceContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import sttp.model.Method
import sttp.model.Uri

/** Concrete OpenAI-compatible SSE transport backed by async-http-client.
  *
  * The non-streaming backend can use sttp's regular cats backend directly
  * because responses are eagerly materialised as strings. For SSE we need a
  * callback-driven client that can push body fragments into an fs2 stream as
  * they arrive. async-http-client is already a transitive dependency of the
  * sttp backend used elsewhere in this module, so we own it directly here and
  * expose a cats-effect/ fs2 surface.
  */
final class AhcOpenAiStreamingTransport[F[_]: Async] private (
    baseUri: Uri,
    client: AsyncHttpClient,
    dispatcher: Dispatcher[F],
    listener: HttpListener[F],
    readTimeout: Duration = SttpOpenAiTransport.DefaultReadTimeout,
) extends OpenAiStreamingTransport[F]:

  override def stream(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): Stream[F, String] =
    stream(path, body, headers, TraceContext.fresh())

  override def stream(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
      trace: TraceContext,
  ): Stream[F, String] =
    val uri = path.split('/').filter(_.nonEmpty).foldLeft(baseUri)(_ addPath _)

    Stream
      .eval(
        for
          queue <- Queue.unbounded[F, Option[Array[Byte]]]
          outcome <- Deferred[F, Either[Throwable, Unit]]
          _ <- listener.onHttpRequest(trace, Method.POST, uri)
          future <- Async[F].delay(startRequest(uri, body, headers, trace, queue, outcome))
        yield (queue, outcome, future)
      )
      .flatMap { case (queue, outcome, future) =>
        val lineStream =
          Stream
            .repeatEval(queue.take)
            .unNoneTerminate
            .flatMap(bytes => Stream.chunk(Chunk.array(bytes)))
            .through(fs2.text.utf8.decode)
            .through(fs2.text.lines)

        (lineStream ++ Stream.eval(outcome.get.rethrow).drain)
          .onFinalize(Async[F].delay(future.cancel(true)).void)
      }

  private def startRequest(
      uri: Uri,
      body: ujson.Value,
      headers: Map[String, String],
      trace: TraceContext,
      queue: Queue[F, Option[Array[Byte]]],
      outcome: Deferred[F, Either[Throwable, Unit]],
  ) =
    val startedAt = System.nanoTime()
    val builder = client
      .preparePost(uri.toString)
      .setBody(ujson.write(body))
      .setHeader("Content-Type", "application/json")
      .setHeader("Accept", "text/event-stream")
      .setRequestTimeout(readTimeout.toMillis.toInt)
      .setReadTimeout(readTimeout.toMillis.toInt)

    headers.foreach { case (name, value) =>
      builder.addHeader(name, value)
    }

    val statusRef = new java.util.concurrent.atomic.AtomicInteger(0)
    val errorBody = new java.lang.StringBuilder()

    def runCallback(effect: F[Unit]): Unit =
      Await.result(
        dispatcher.unsafeToFuture(effect),
        scala.concurrent.duration.Duration.Inf,
      )

    builder.execute(new AsyncHandler[Unit]:
      override def onStatusReceived(status: HttpResponseStatus): AsyncHandler.State =
        statusRef.set(status.getStatusCode)
        AsyncHandler.State.CONTINUE

      override def onHeadersReceived(
          httpHeaders: io.netty.handler.codec.http.HttpHeaders
      ): AsyncHandler.State =
        AsyncHandler.State.CONTINUE

      override def onBodyPartReceived(
          part: HttpResponseBodyPart
      ): AsyncHandler.State =
        if statusRef.get >= 200 && statusRef.get < 300 then
          runCallback(queue.offer(Some(part.getBodyPartBytes())))
        else
          errorBody.append(new String(part.getBodyPartBytes(), java.nio.charset.StandardCharsets.UTF_8))
        AsyncHandler.State.CONTINUE

      override def onThrowable(error: Throwable): Unit =
        val duration = System.nanoTime() - startedAt
        runCallback(
          (listener.onHttpFailure(trace, Method.POST, uri, error, duration) *>
            queue.offer(None) *>
            outcome.complete(Left(error)).void)
        )

      override def onCompleted(): Unit =
        val status = statusRef.get
        val duration = System.nanoTime() - startedAt
        val effect =
          if status >= 200 && status < 300 then
            listener.onHttpResponse(trace, Method.POST, uri, status, duration) *>
              queue.offer(None) *>
              outcome.complete(Right(())).void
          else
            val error = OpenAiHttpError.fromResponse(status, errorBody.toString)
            listener.onHttpResponse(trace, Method.POST, uri, status, duration) *>
              queue.offer(None) *>
              outcome.complete(Left(error)).void

        runCallback(effect)
    )

object AhcOpenAiStreamingTransport:
  def resource[F[_]: Async](
      baseUri: Uri,
  ): Resource[F, OpenAiStreamingTransport[F]] =
    resource(baseUri, HttpListener.noop[F], SttpOpenAiTransport.DefaultReadTimeout)

  def resource[F[_]: Async](
      baseUri: Uri,
      readTimeout: Duration,
  ): Resource[F, OpenAiStreamingTransport[F]] =
    resource(baseUri, HttpListener.noop[F], readTimeout)

  def resource[F[_]: Async](
      baseUri: Uri,
      listener: HttpListener[F],
      readTimeout: Duration,
  ): Resource[F, OpenAiStreamingTransport[F]] =
    for
      dispatcher <- Dispatcher.parallel[F]
      client <- Resource.make(
        Async[F].delay {
          val timeoutMillis = readTimeout.toMillis.toInt
          val config = new DefaultAsyncHttpClientConfig.Builder()
            .setRequestTimeout(timeoutMillis)
            .setReadTimeout(timeoutMillis)
            .build()
          new DefaultAsyncHttpClient(config)
        }
      )(client => Async[F].delay(client.close()))
    yield new AhcOpenAiStreamingTransport[F](baseUri, client, dispatcher, listener, readTimeout)
