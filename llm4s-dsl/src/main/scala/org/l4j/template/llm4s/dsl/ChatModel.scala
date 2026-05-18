package org.l4j.template.llm4s.dsl

import cats.MonadThrow
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse

object ChatModel:
  def apply[F[_]: MonadThrow]: Runnable[F, ChatRequest, ChatResponse] =
    Runnable.eval { (request, ctx) =>
      MonadThrow[F].flatMap(
        ctx.runtimeListener.onProviderRequest(ctx.traceContext, turn = 0, request = request)
      ) { _ =>
        val started = System.nanoTime()
        MonadThrow[F].flatMap(ctx.backend.chat(request, ctx.traceContext)) { response =>
          val duration = System.nanoTime() - started
          MonadThrow[F].map(
            ctx.runtimeListener.onProviderResponse(
              trace = ctx.traceContext,
              turn = 0,
              response = response,
              durationNanos = duration,
            )
          )(_ => response)
        }
      }
    }
