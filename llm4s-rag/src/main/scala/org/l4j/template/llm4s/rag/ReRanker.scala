package org.l4j.template.llm4s.rag

import cats.Applicative

trait ReRanker[F[_]]:
  def rerank(query: String, sources: List[RetrievedSource]): F[List[RetrievedSource]]

object ReRanker:
  def identity[F[_]: Applicative]: ReRanker[F] =
    new ReRanker[F]:
      override def rerank(query: String, sources: List[RetrievedSource]): F[List[RetrievedSource]] =
        Applicative[F].pure(sources)

  def byScore[F[_]: Applicative]: ReRanker[F] =
    new ReRanker[F]:
      override def rerank(query: String, sources: List[RetrievedSource]): F[List[RetrievedSource]] =
        Applicative[F].pure(sources.sortBy(source => -source.score))

