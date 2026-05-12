package org.l4j.template.llm4s.memory

import cats.Monad
import cats.syntax.flatMap.*
import org.l4j.template.llm4s.core.ChatMessage

trait ChatMemory[F[_], Id]:
  def messages(id: Id): F[List[ChatMessage]]
  def replace(id: Id, messages: List[ChatMessage]): F[Unit]

  def append(id: Id, messages: List[ChatMessage])(using F: Monad[F]): F[Unit] =
    this.messages(id).flatMap(existing => replace(id, existing ++ messages))
