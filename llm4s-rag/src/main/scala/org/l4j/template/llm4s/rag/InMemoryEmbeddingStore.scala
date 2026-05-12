package org.l4j.template.llm4s.rag

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.all.*

final class InMemoryEmbeddingStore[F[_]: Sync] private (
    state: Ref[F, Map[String, EmbeddingRecord]]
) extends EmbeddingStore[F]:

  override def add(records: List[EmbeddingRecord]): F[Unit] =
    state.update(existing => existing ++ records.map(record => record.id -> record).toMap)

  override def search(
      query: EmbeddingVector,
      maxResults: Int,
      minScore: Option[Double] = None,
  ): F[List[RetrievedSource]] =
    state.get.map { records =>
      records.values.toList
        .map { record =>
          RetrievedSource(
            id = record.id,
            text = record.text,
            score = record.embedding.cosineSimilarity(query),
            metadata = record.metadata,
          )
        }
        .filter(source => minScore.forall(source.score >= _))
        .sortBy(source => -source.score)
        .take(maxResults.max(0))
    }

  override def remove(ids: Set[String]): F[Unit] =
    state.update(existing => existing -- ids)

object InMemoryEmbeddingStore:
  def create[F[_]: Sync]: F[InMemoryEmbeddingStore[F]] =
    Ref.of[F, Map[String, EmbeddingRecord]](Map.empty).map(new InMemoryEmbeddingStore[F](_))

