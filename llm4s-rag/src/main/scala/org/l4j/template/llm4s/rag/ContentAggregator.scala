package org.l4j.template.llm4s.rag

trait ContentAggregator:
  def aggregate(sources: List[RetrievedSource]): List[RetrievedSource]

object ContentAggregator:
  val dedupeByIdKeepBestScore: ContentAggregator =
    new ContentAggregator:
      override def aggregate(sources: List[RetrievedSource]): List[RetrievedSource] =
        sources
          .groupBy(_.id)
          .values
          .map { group =>
            val best = group.maxBy(_.score)
            val mergedMetadata = group.foldLeft(Map.empty[String, String]) { (acc, source) =>
              source.metadata.foldLeft(acc) {
                case (m, (k, _)) if m.contains(k) => m
                case (m, (k, v))                  => m.updated(k, v)
              }
            } ++ best.metadata
            best.copy(metadata = mergedMetadata)
          }
          .toList
          .sortBy(source => -source.score)

