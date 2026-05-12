package org.l4j.template.llm4s.memory

import cats.Monad
import cats.syntax.flatMap.*
import org.l4j.template.llm4s.core.ChatMessage

final class MessageWindowMemory[F[_]: Monad, Id](
    underlying: ChatMemory[F, Id],
    maxMessages: Int,
) extends ChatMemory[F, Id]:
  require(maxMessages > 0, s"maxMessages must be > 0, got $maxMessages")

  override def messages(id: Id): F[List[ChatMessage]] =
    underlying.messages(id)

  override def replace(id: Id, messages: List[ChatMessage]): F[Unit] =
    underlying.replace(id, trim(messages))

  override def append(id: Id, messages: List[ChatMessage])(using F: Monad[F]): F[Unit] =
    underlying.messages(id).flatMap(existing => underlying.replace(id, trim(existing ++ messages)))

  private def trim(messages: List[ChatMessage]): List[ChatMessage] =
    messages.takeRight(maxMessages)
