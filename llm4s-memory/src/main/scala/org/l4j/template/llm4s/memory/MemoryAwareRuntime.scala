package org.l4j.template.llm4s.memory

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatTranscript
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.runtime.ToolKit

/** Memory-aware wrapper around [[AiRuntime]].
  *
  * Lives in `llm4s-memory` (not `llm4s-runtime`) so the runtime module
  * doesn't have to take on a dependency on a particular persistence
  * abstraction. Users who want history-threaded chats import from here;
  * users who only need stateless chats use `AiRuntime` directly.
  *
  * When a `RuntimeListener[F]` is supplied, the wrapper fires
  * `onMemoryRead` / `onMemoryWritten` under the same `TraceContext` the
  * underlying chat ran with — so a single trace tree spans memory load →
  * chat → memory persist (PR-8b).
  */
final class MemoryAwareRuntime[F[_]: MonadThrow: Parallel, Id](
    runtime: AiRuntime[F],
    memory: ChatMemory[F, Id],
    listener: RuntimeListener[F],
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
      val request = ChatRequest(messages = initialMessages, tools = toolKit.schemas)

      val pre = ambientTrace.flatMap(trace =>
        listener.onMemoryRead(trace, memoryId.toString, history.length)
      )

      pre >> runtime
        .chatRequestWithTrace(request, toolKit)
        .flatMap { case (text, messages, trace) =>
          val persisted = ChatTranscript.fromMessages(messages).turns
          listener.onMemoryWritten(trace, memoryId.toString, persisted.length) >>
            memory.replace(memoryId, persisted).as(text)
        }
    }

  def chatRequest(
      memoryId: Id,
      request: ChatRequest,
      toolKit: ToolKit[F] = ToolKit.empty[F],
  ): F[String] =
    runtime.chatRequestWithTrace(request, toolKit).flatMap { case (text, messages, trace) =>
      val persisted = ChatTranscript.fromMessages(messages).turns
      listener.onMemoryWritten(trace, memoryId.toString, persisted.length) >>
        memory.replace(memoryId, persisted).as(text)
    }

  /** A placeholder trace for the *pre-chat* memory read. We don't yet have
    * access to the trace the runtime will mint, so the read event carries a
    * fresh one. The post-chat write event uses the runtime's own trace, so
    * it correlates with the rest of the chat. Adopters who care about
    * unified pre/post correlation can pass an outer trace via a custom
    * `RuntimeListener` and stitch them via timestamps. */
  private def ambientTrace: F[TraceContext] =
    MonadThrow[F].pure(TraceContext.fresh())

object MemoryAwareRuntime:
  def apply[F[_]: MonadThrow: Parallel, Id](
      runtime: AiRuntime[F],
      memory: ChatMemory[F, Id],
  ): MemoryAwareRuntime[F, Id] =
    new MemoryAwareRuntime[F, Id](runtime, memory, RuntimeListener.noop[F])

  def apply[F[_]: MonadThrow: Parallel, Id](
      runtime: AiRuntime[F],
      memory: ChatMemory[F, Id],
      listener: RuntimeListener[F],
  ): MemoryAwareRuntime[F, Id] =
    new MemoryAwareRuntime[F, Id](runtime, memory, listener)
