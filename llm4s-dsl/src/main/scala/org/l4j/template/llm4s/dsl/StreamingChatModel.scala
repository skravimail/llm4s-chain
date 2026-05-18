package org.l4j.template.llm4s.dsl

import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.syntax.all.*
import fs2.Stream
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.streaming.StreamEvent

object StreamingChatModel:
  def apply[F[_]: Sync]: StreamingRunnable[F, ChatRequest, StreamEvent] =
    StreamingRunnable.leaf("streaming-chat-model") { (request, ctx) =>
      ctx.streamingBackend match
        case None =>
          Stream.raiseError[F](
            IllegalStateException("RunContext is missing a StreamingChatBackend")
          )
        case Some(backend) =>
          Stream.eval(
            for
              startNs <- Sync[F].delay(System.nanoTime())
              count <- Ref.of[F, Long](0L)
              _ <- ctx.runtimeListener.onStreamStarted(ctx.traceContext, request)
            yield (startNs, count)
          ).flatMap { case (startNs, count) =>
            backend
              .stream(request)
              .evalTap(_ => count.update(_ + 1L))
              .onFinalizeCase {
                case Resource.ExitCase.Succeeded =>
                  for
                    n <- count.get
                    _ <- ctx.runtimeListener.onStreamCompleted(
                      ctx.traceContext,
                      request,
                      n,
                      System.nanoTime() - startNs,
                    )
                  yield ()
                case Resource.ExitCase.Errored(error) =>
                  for
                    n <- count.get
                    _ <- ctx.runtimeListener.onStreamFailed(
                      ctx.traceContext,
                      request,
                      error,
                      n,
                      System.nanoTime() - startNs,
                    )
                  yield ()
                case Resource.ExitCase.Canceled =>
                  for
                    n <- count.get
                    _ <- ctx.runtimeListener.onStreamFailed(
                      ctx.traceContext,
                      request,
                      new InterruptedException("stream cancelled"),
                      n,
                      System.nanoTime() - startNs,
                    )
                  yield ()
              }
          }
    }
