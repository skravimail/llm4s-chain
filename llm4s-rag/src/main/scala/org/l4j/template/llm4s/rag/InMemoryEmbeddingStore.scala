package org.l4j.template.llm4s.rag

import cats.effect.Ref
import cats.effect.Sync

final class InMemoryEmbeddingStore[F[_]: Sync] private (
    state: Ref[F, Map[(Option[String], String), EmbeddingRecord]]
) extends EmbeddingStore[F]:

  override def add(records: List[EmbeddingRecord]): F[Unit] =
    val normalized = records.map(record => record.copy(embedding = record.embedding.normalize))
    state.update { existing =>
      existing ++ normalized.map(record => (record.namespace, record.id) -> record).toMap
    }

  override def search(query: RetrievalQuery): F[List[RetrievedSource]] =
    val normalizedQuery = query.vector.normalize
    Sync[F].map(state.get) { records =>
      records.values.toList
        .filter(record => query.namespace.forall(namespace => record.namespace.contains(namespace)))
        .filter(record => query.filter.forall(_.matches(record.metadata)))
        .map { record =>
          RetrievedSource(
            id = record.id,
            text = record.text,
            metadata = record.metadata,
            namespace = record.namespace,
            score = record.embedding.cosineSimilarity(normalizedQuery),
          )
        }
        .filter(source => query.minScore.forall(source.score >= _))
        .sortBy(source => -source.score)
        .take(query.maxResults.max(0))
    }

  override def remove(ids: Set[String]): F[Unit] =
    state.update(_.filterNot { case ((_, id), _) => ids.contains(id) })

object InMemoryEmbeddingStore:
  def create[F[_]: Sync]: F[InMemoryEmbeddingStore[F]] =
    Sync[F].map(Ref.of[F, Map[(Option[String], String), EmbeddingRecord]](Map.empty))(new InMemoryEmbeddingStore[F](_))
