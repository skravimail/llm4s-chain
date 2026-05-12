package org.l4j.template.llm4s.rag

import cats.Applicative

trait QueryRouter[F[_]]:
  def route(query: String): F[List[ContentRetriever[F]]]

object QueryRouter:
  def static[F[_]: Applicative](retrievers: List[ContentRetriever[F]]): QueryRouter[F] =
    new QueryRouter[F]:
      override def route(query: String): F[List[ContentRetriever[F]]] =
        Applicative[F].pure(retrievers)

  def from[F[_]](select: String => F[List[ContentRetriever[F]]]): QueryRouter[F] =
    new QueryRouter[F]:
      override def route(query: String): F[List[ContentRetriever[F]]] =
        select(query)

