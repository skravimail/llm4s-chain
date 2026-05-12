package org.l4j.template.llm4s.rag

final case class EmbeddingVector(values: Vector[Double]):
  require(values.nonEmpty, "EmbeddingVector must contain at least one value")

  def cosineSimilarity(other: EmbeddingVector): Double =
    require(
      values.length == other.values.length,
      s"Embedding dimensions differ: ${values.length} != ${other.values.length}",
    )

    val dot = values.zip(other.values).map(_ * _).sum
    val leftMagnitude = math.sqrt(values.map(value => value * value).sum)
    val rightMagnitude = math.sqrt(other.values.map(value => value * value).sum)
    if leftMagnitude == 0.0 || rightMagnitude == 0.0 then 0.0
    else dot / (leftMagnitude * rightMagnitude)

  def toPgVectorLiteral: String =
    values.mkString("[", ",", "]")

object EmbeddingVector:
  def of(values: Double*): EmbeddingVector =
    EmbeddingVector(values.toVector)

