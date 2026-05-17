package org.l4j.template.llm4s.streaming

import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*
import fs2.Stream
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.runtime.ToolKit

/** Streaming counterpart to `AiRuntime`. Emits events via the same
  * `RuntimeListener` as the non-streaming runtime (PR-8c) — the per-chunk
  * stream is too chatty to surface event-by-event, so we bracket the
  * stream and report total event count + duration on completion.
  */
final class StreamingAiRuntime[F[_]: Sync](
    backend: StreamingChatBackend[F],
    listener: RuntimeListener[F],
):

  def stream(
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): TokenStream[F] =
    val initialMessages =
      system.map(ChatMessage.SystemMessage.from).toList :+ ChatMessage.UserMessage.from(userText)
    val request = ChatRequest(
      messages = initialMessages,
      tools = toolKit.schemas,
    )

    TokenStream(instrumented(request))

  private def instrumented(request: ChatRequest): Stream[F, StreamEvent] =
    Stream.eval(
      for
        trace <- Sync[F].delay(TraceContext.fresh())
        startNs <- Sync[F].delay(System.nanoTime())
        count <- Ref.of[F, Long](0L)
        _ <- listener.onStreamStarted(trace, request)
      yield (trace, startNs, count)
    ).flatMap { case (trace, startNs, count) =>
      backend
        .stream(request)
        .evalTap(_ => count.update(_ + 1L))
        .onFinalizeCase {
          case Resource.ExitCase.Succeeded =>
            for
              n <- count.get
              _ <- listener.onStreamCompleted(trace, request, n, System.nanoTime() - startNs)
            yield ()
          case Resource.ExitCase.Errored(e) =>
            for
              n <- count.get
              _ <- listener.onStreamFailed(trace, request, e, n, System.nanoTime() - startNs)
            yield ()
          case Resource.ExitCase.Canceled =>
            for
              n <- count.get
              _ <- listener.onStreamFailed(
                trace,
                request,
                new InterruptedException("stream cancelled"),
                n,
                System.nanoTime() - startNs,
              )
            yield ()
        }
    }

object StreamingAiRuntime:
  def apply[F[_]: Sync](backend: StreamingChatBackend[F]): StreamingAiRuntime[F] =
    new StreamingAiRuntime[F](backend, RuntimeListener.noop[F])

  def apply[F[_]: Sync](
      backend: StreamingChatBackend[F],
      listener: RuntimeListener[F],
  ): StreamingAiRuntime[F] =
    new StreamingAiRuntime[F](backend, listener)
