package org.l4j.template.llm4s.structured

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit

object StructuredOutputRuntime:

  def chat[F[_]: MonadThrow: Parallel, A](
      backend: ChatBackend[F],
      config: RuntimeConfig,
      system: Option[String],
      userText: String,
      toolKit: ToolKit[F],
  )(using codec: StructuredCodec[A]): F[A] =
    val initialMessages =
      system.map(ChatMessage.SystemMessage.from).toList :+ ChatMessage.UserMessage.from(userText)
    val request = ChatRequest(
      messages = initialMessages,
      tools = toolKit.schemas,
      responseFormat = Some(
        ResponseFormat.JsonSchema(
          name = codec.schemaName,
          schema = codec.schema,
          strict = true,
        )
      ),
    )

    AiRuntime[F](backend, config)
      .chatRequest(request, toolKit)
      .flatMap { raw =>
        codec.decode(raw) match
          case Right(value) => MonadThrow[F].pure(value)
          case Left(error)  => MonadThrow[F].raiseError(RuntimeException(error))
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
    memory.messages(memoryId).flatMap { history =>
      val initialMessages =
        system.map(ChatMessage.SystemMessage.from).toList ++ history ++ List(ChatMessage.UserMessage.from(userText))

      val request = ChatRequest(
        messages = initialMessages,
        tools = toolKit.schemas,
        responseFormat = Some(
          ResponseFormat.JsonSchema(
            name = codec.schemaName,
            schema = codec.schema,
            strict = true,
          )
        ),
      )

      AiRuntime[F](backend, config)
        .chatRequestWithMemory(memory, memoryId, request, toolKit)
        .flatMap { raw =>
          codec.decode(raw) match
            case Right(value) => MonadThrow[F].pure(value)
            case Left(error)  => MonadThrow[F].raiseError(RuntimeException(error))
        }
    }
