package org.l4j.template.llm4s.rag

import cats.Monad

trait ContentRetriever[F[_]]:
  def retrieve(query: String): F[List[RetrievedSource]]

final class EmbeddingContentRetriever[F[_]: Monad](
    embeddingModel: EmbeddingModel[F],
    embeddingStore: EmbeddingStore[F],
    maxResults: Int = 4,
    minScore: Option[Double] = None,
    namespace: Option[String] = None,
    filter: Option[MetadataFilter] = None,
) extends ContentRetriever[F]:

  override def retrieve(query: String): F[List[RetrievedSource]] =
    Monad[F].flatMap(embeddingModel.embed(query)) { vector =>
      embeddingStore.search(
        RetrievalQuery(
          vector = vector,
          maxResults = maxResults,
          minScore = minScore,
          namespace = namespace,
          filter = filter,
        )
      )
    }
