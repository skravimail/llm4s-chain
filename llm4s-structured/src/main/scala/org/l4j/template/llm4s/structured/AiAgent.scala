package org.l4j.template.llm4s.structured

import cats.MonadThrow
import cats.Parallel
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit

final class AiAgent[F[_]: MonadThrow: Parallel](
    backend: ChatBackend[F],
    val tools: ToolKit[F],
    val config: RuntimeConfig,
):

  private val runtime: AiRuntime[F] = AiRuntime[F](backend, config)

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

  def withTools(tk: ToolKit[F]): AiAgent[F] = new AiAgent[F](backend, tk, config)

  def withConfig(c: RuntimeConfig): AiAgent[F] = new AiAgent[F](backend, tools, c)

object AiAgent:

  def apply[F[_]: MonadThrow: Parallel](backend: ChatBackend[F]): AiAgent[F] =
    new AiAgent[F](backend, ToolKit.empty[F], RuntimeConfig())

  def apply[F[_]: MonadThrow: Parallel](backend: ChatBackend[F], tools: ToolKit[F]): AiAgent[F] =
    new AiAgent[F](backend, tools, RuntimeConfig())

  def apply[F[_]: MonadThrow: Parallel](
      backend: ChatBackend[F],
      tools: ToolKit[F],
      config: RuntimeConfig,
  ): AiAgent[F] =
    new AiAgent[F](backend, tools, config)
