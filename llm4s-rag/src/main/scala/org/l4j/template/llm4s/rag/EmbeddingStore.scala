package org.l4j.template.llm4s.rag

trait EmbeddingStore[F[_]]:
  def add(records: List[EmbeddingRecord]): F[Unit]

  def search(
      query: EmbeddingVector,
      maxResults: Int,
      minScore: Option[Double] = None,
  ): F[List[RetrievedSource]]

  def remove(ids: Set[String]): F[Unit]

