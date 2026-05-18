package org.l4j.template.llm4s.rag

enum MetadataFilter:
  case Eq(key: String, value: String)
  case In(key: String, values: Set[String])
  case And(filters: List[MetadataFilter])
  case Or(filters: List[MetadataFilter])

  def matches(metadata: Map[String, String]): Boolean =
    this match
      case MetadataFilter.Eq(key, value) =>
        metadata.get(key).contains(value)
      case MetadataFilter.In(key, values) =>
        metadata.get(key).exists(values.contains)
      case MetadataFilter.And(filters) =>
        filters.forall(_.matches(metadata))
      case MetadataFilter.Or(filters) =>
        filters.exists(_.matches(metadata))

final case class RetrievalQuery(
    vector: EmbeddingVector,
    maxResults: Int = 4,
    minScore: Option[Double] = None,
    namespace: Option[String] = None,
    filter: Option[MetadataFilter] = None,
)

object RetrievalQuery:
  def basic(
      vector: EmbeddingVector,
      maxResults: Int,
      minScore: Option[Double] = None,
  ): RetrievalQuery =
    RetrievalQuery(vector = vector, maxResults = maxResults, minScore = minScore)
