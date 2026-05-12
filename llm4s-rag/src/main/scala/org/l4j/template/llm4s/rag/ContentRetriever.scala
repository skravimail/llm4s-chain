package org.l4j.template.llm4s.rag

import cats.Monad
import cats.syntax.all.*

trait ContentRetriever[F[_]]:
  def retrieve(query: String): F[List[RetrievedSource]]

final class EmbeddingContentRetriever[F[_]: Monad](
    embeddingModel: EmbeddingModel[F],
    embeddingStore: EmbeddingStore[F],
    maxResults: Int = 4,
    minScore: Option[Double] = None,
) extends ContentRetriever[F]:

  override def retrieve(query: String): F[List[RetrievedSource]] =
    embeddingModel
      .embed(query)
      .flatMap(embeddingStore.search(_, maxResults, minScore))

