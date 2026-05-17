package org.l4j.template.llm4s.structured

import cats.MonadThrow
import cats.Parallel
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit

/** An agent is a thin facade over a single [[AiRuntime]] plus per-agent
  * state (its toolkit). Derived agents from `withTools` share the same
  * runtime instance — see PR-12 in CODE_REVIEW.md. `withConfig` legitimately
  * builds a new runtime because the config is baked into it.
  */
final class AiAgent[F[_]: MonadThrow: Parallel] private (
    val backend: ChatBackend[F],
    val tools: ToolKit[F],
    val config: RuntimeConfig,
    val runtime: AiRuntime[F],
):

  def chat(system: String, user: String): F[String] =
    runtime.chat(Some(system), user, tools)

  def chatAs[A](system: String, user: String)(using StructuredCodec[A]): F[A] =
    StructuredOutputRuntime.chat[F, A](backend, config, Some(system), user, tools)

  def chatWithMemory[Id](
      memory: ChatMemory[F, Id],
      memoryId: Id,
      system: String,
      user: String,
  ): F[String] =
    runtime.chatWithMemory(memory, memoryId, Some(system), user, tools)

  def chatAsWithMemory[A, Id](
      memory: ChatMemory[F, Id],
      memoryId: Id,
      system: String,
      user: String,
  )(using StructuredCodec[A]): F[A] =
    StructuredOutputRuntime.chatWithMemory[F, A, Id](
      backend = backend,
      config = config,
      memory = memory,
      memoryId = memoryId,
      system = Some(system),
      userText = user,
      toolKit = tools,
    )

  /** Reuse the same underlying runtime — important so that any state the
    * runtime acquires (rate limiters, in-flight counters, listeners) is
    * shared across derived agents instead of silently duplicated. */
  def withTools(tk: ToolKit[F]): AiAgent[F] =
    new AiAgent[F](backend, tk, config, runtime)

  /** A new config requires a new runtime because the config is baked into
    * it; this is the *one* boundary where copying is correct. */
  def withConfig(c: RuntimeConfig): AiAgent[F] =
    new AiAgent[F](backend, tools, c, AiRuntime[F](backend, c))

object AiAgent:

  def apply[F[_]: MonadThrow: Parallel](backend: ChatBackend[F]): AiAgent[F] =
    apply(backend, ToolKit.empty[F], RuntimeConfig())

  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      tools: ToolKit[F],
  ): AiAgent[F] =
    apply(backend, tools, RuntimeConfig())

  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      tools: ToolKit[F],
      config: RuntimeConfig,
  ): AiAgent[F] =
    new AiAgent[F](backend, tools, config, AiRuntime[F](backend, config))
