package org.l4j.template.llm4s.memory

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatTranscript
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.ToolKit

/** Memory-aware wrapper around [[AiRuntime]].
  *
  * Lives in `llm4s-memory` (not `llm4s-runtime`) so the runtime module
  * doesn't have to take on a dependency on a particular persistence
  * abstraction. Users who want history-threaded chats import from here;
  * users who only need stateless chats use `AiRuntime` directly.
  */
final class MemoryAwareRuntime[F[_]: MonadThrow: Parallel, Id](
    runtime: AiRuntime[F],
    memory: ChatMemory[F, Id],
):

  def chat(
      memoryId: Id,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    memory.messages(memoryId).flatMap { history =>
      val initialMessages =
        system.map(ChatMessage.SystemMessage.from).toList ++
          history ++
          List(ChatMessage.UserMessage.from(userText))

      runtime
        .chatRequestWithMessages(
          ChatRequest(messages = initialMessages, tools = toolKit.schemas),
          toolKit,
        )
        .flatMap { case (text, messages) =>
          val persisted = ChatTranscript.fromMessages(messages).turns
          memory.replace(memoryId, persisted).as(text)
        }
    }

  def chatRequest(
      memoryId: Id,
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    runtime.chatRequestWithMessages(request, toolKit).flatMap { case (text, messages) =>
      memory.replace(memoryId, ChatTranscript.fromMessages(messages).turns).as(text)
    }

object MemoryAwareRuntime:
  def apply[F[_]: MonadThrow: Parallel, Id](
      runtime: AiRuntime[F],
      memory: ChatMemory[F, Id],
  ): MemoryAwareRuntime[F, Id] =
    new MemoryAwareRuntime[F, Id](runtime, memory)
