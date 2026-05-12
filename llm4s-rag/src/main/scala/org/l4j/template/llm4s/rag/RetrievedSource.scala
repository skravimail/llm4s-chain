package org.l4j.template.llm4s.rag

final case class EmbeddingRecord(
    id: String,
    text: String,
    embedding: EmbeddingVector,
    metadata: Map[String, String] = Map.empty,
)

final case class RetrievedSource(
    id: String,
    text: String,
    score: Double,
    metadata: Map[String, String] = Map.empty,
)

