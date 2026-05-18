package org.l4j.template.llm4s.dsl

import cats.MonadThrow
import cats.Parallel
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.structured.AiAgent
import org.l4j.template.llm4s.structured.ChatOptions

object AiAgentRunnable:
  def apply[F[_]: cats.Functor: MonadThrow: Parallel](
      agent: AiAgent[F],
      opts: ChatOptions[F] = ChatOptions.empty[F],
  ): Runnable[F, ChatRequest, String] =
    Runnable.eval { (request, _) =>
      agent.chat(request, opts)
    }

  def fromContext[F[_]: cats.Functor: MonadThrow: Parallel](
      tools: ToolKit[F] = ToolKit.empty[F],
      opts: ChatOptions[F] = ChatOptions.empty[F],
  ): Runnable[F, ChatRequest, String] =
    Runnable.eval { (request, ctx) =>
      AiAgent[F](
        backend = ctx.backend,
        tools = tools,
        config = ctx.runtimeConfig,
        listener = ctx.runtimeListener,
      ).chat(request, opts)
    }
