package org.l4j.template.llm4s.structured

import cats.MonadThrow
import cats.Parallel
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.memory.MemoryAwareRuntime
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
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
    MemoryAwareRuntime(runtime, memory).chat(memoryId, Some(system), user, tools)

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

  /** The unified entry point: send any `ChatRequest` with per-call overrides
    * via [[ChatOptions]]. Use this when the convenience `chat(system, user)`
    * isn't expressive enough — e.g. multi-turn user input, a one-off toolkit
    * swap, or pinning `responseFormat` for a single call.
    */
  def chat(request: ChatRequest, opts: ChatOptions[F]): F[String] =
    val effectiveTools = opts.toolKit.getOrElse(tools)
    val merged = request.copy(
      tools = effectiveTools.schemas,
      temperature = opts.temperature.orElse(request.temperature),
      responseFormat = opts.responseFormat.orElse(request.responseFormat),
      metadata = request.metadata ++ opts.metadata,
    )
    runtime.chatRequest(merged, effectiveTools)

  /** Convenience overload taking a pre-built list of messages plus options. */
  def chat(messages: List[ChatMessage], opts: ChatOptions[F] = ChatOptions.empty[F]): F[String] =
    chat(ChatRequest(messages = messages), opts)

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

  /** Listener-aware overload — every chat the agent runs fires the given
    * `RuntimeListener`'s events (chat / provider / tool). Use this to plug
    * tracing or metrics in once at construction time instead of wrapping
    * every call site. */
  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      tools: ToolKit[F],
      config: RuntimeConfig,
      listener: RuntimeListener[F],
  ): AiAgent[F] =
    new AiAgent[F](backend, tools, config, AiRuntime[F](backend, config, listener))
