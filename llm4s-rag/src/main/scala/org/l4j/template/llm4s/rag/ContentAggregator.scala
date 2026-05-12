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
          .map(_.maxBy(_.score))
          .toList
          .sortBy(source => -source.score)

