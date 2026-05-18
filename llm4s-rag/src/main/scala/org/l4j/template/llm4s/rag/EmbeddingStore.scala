package org.l4j.template.llm4s.rag

trait EmbeddingStore[F[_]]:
  def add(records: List[EmbeddingRecord]): F[Unit]

  def search(query: RetrievalQuery): F[List[RetrievedSource]]

  def search(
      query: EmbeddingVector,
      maxResults: Int,
      minScore: Option[Double] = None,
  ): F[List[RetrievedSource]] =
    search(RetrievalQuery(query, maxResults = maxResults, minScore = minScore))

  def remove(ids: Set[String]): F[Unit]
