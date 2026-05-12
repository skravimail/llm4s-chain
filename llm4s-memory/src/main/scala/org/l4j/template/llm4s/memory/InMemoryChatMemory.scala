package org.l4j.template.llm4s.memory

import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.syntax.functor.*
import org.l4j.template.llm4s.core.ChatMessage

final class InMemoryChatMemory[F[_]: Sync, Id] private (
    state: Ref[F, Map[Id, List[ChatMessage]]]
) extends ChatMemory[F, Id]:

  override def messages(id: Id): F[List[ChatMessage]] =
    state.get.map(_.getOrElse(id, Nil))

  override def replace(id: Id, messages: List[ChatMessage]): F[Unit] =
    state.update(_.updated(id, messages))

object InMemoryChatMemory:
  def create[F[_]: Sync, Id]: F[InMemoryChatMemory[F, Id]] =
    Ref.of[F, Map[Id, List[ChatMessage]]](Map.empty).map(new InMemoryChatMemory[F, Id](_))
