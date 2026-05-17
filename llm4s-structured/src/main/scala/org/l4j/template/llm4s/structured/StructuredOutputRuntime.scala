package org.l4j.template.llm4s.structured

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.memory.MemoryAwareRuntime
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit

object StructuredOutputRuntime:

  /** Build the JSON-schema-bearing `ChatRequest` for this codec — shared by
    * the runtime-taking and backend-taking entry points so they agree on
    * the wire shape. */
  private def buildRequest[A](
      messages: List[ChatMessage],
      toolKit: ToolKit[?],
      codec: StructuredCodec[A],
  ): ChatRequest =
    ChatRequest(
      messages = messages,
      tools = toolKit.schemas,
      responseFormat = Some(
        ResponseFormat.JsonSchema(
          name = codec.schemaName,
          schema = codec.schema,
          strict = true,
        )
      ),
    )

  private def decodeOrRaise[F[_]: MonadThrow, A](raw: String)(using codec: StructuredCodec[A]): F[A] =
    codec.decode(raw) match
      case Right(value) => MonadThrow[F].pure(value)
      case Left(error)  => MonadThrow[F].raiseError(RuntimeException(error))

  /** Run a structured chat through a caller-supplied `AiRuntime`.
    *
    * Prefer this when the runtime carries observability state — a wired
    * `RuntimeListener`, a memory wrapper, etc. — because the
    * `(backend, config)` overload constructs a fresh, listener-less
    * runtime internally and loses that state. */
  def chat[F[_]: MonadThrow: Parallel, A](
      runtime: AiRuntime[F],
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F],
  )(using codec: StructuredCodec[A]): F[A] =
    val messages =
      system.map(ChatMessage.SystemMessage.from).toList :+ ChatMessage.UserMessage.from(userText)
    runtime
      .chatRequest(buildRequest(messages, toolKit, codec), toolKit)
      .flatMap(decodeOrRaise[F, A])

  /** Back-compat overload: builds a noop-listener runtime from
    * `(backend, config)`. Prefer the runtime-taking overload above when
    * you have one. */
  def chat[F[_]: MonadThrow: Parallel, A](
      backend: ChatBackend[F],
      config: RuntimeConfig,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F],
  )(using codec: StructuredCodec[A]): F[A] =
    chat(AiRuntime[F](backend, config), system, userText, toolKit)

  def chatWithMemory[F[_]: MonadThrow: Parallel, A, Id](
      runtime: AiRuntime[F],
      memory: ChatMemory[F, Id],
      memoryId: Id,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F],
  )(using codec: StructuredCodec[A]): F[A] =
    memory.messages(memoryId).flatMap { history =>
      val messages =
        system.map(ChatMessage.SystemMessage.from).toList ++ history ++
          List(ChatMessage.UserMessage.from(userText))
      MemoryAwareRuntime(runtime, memory)
        .chatRequest(memoryId, buildRequest(messages, toolKit, codec), toolKit)
        .flatMap(decodeOrRaise[F, A])
    }

  def chatWithMemory[F[_]: MonadThrow: Parallel, A, Id](
      backend: ChatBackend[F],
      config: RuntimeConfig,
      memory: ChatMemory[F, Id],
      memoryId: Id,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F],
  )(using codec: StructuredCodec[A]): F[A] =
    chatWithMemory(AiRuntime[F](backend, config), memory, memoryId, system, userText, toolKit)
