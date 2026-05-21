package org.l4j.template.llm4s.core

final case class ModelPricing(
    inputTokenPricePerMillion: Double,
    outputTokenPricePerMillion: Double
)

object ModelPricing:
  private val pricingMap: Map[String, (Double, Double)] = Map(
    "gpt-4o-mini"      -> (0.15, 0.60),
    "gpt-4o"           -> (5.00, 15.00),
    "gemini-1.5-flash" -> (0.075, 0.30),
    "gemini-2.5-flash" -> (0.075, 0.30),
    "gemini-1.5-pro"   -> (1.25, 5.00),
    "gemini-2.5-pro"   -> (1.25, 5.00)
  )

  def calculateCost(model: String, inputTokens: Int, outputTokens: Int): Double =
    val normalized = model.toLowerCase.trim
    // Match the pricing by checking if the model name contains our keys
    // Sort keys by length descending to match more specific variants first (e.g. gpt-4o-mini before gpt-4o)
    val matched = pricingMap.keys.toList
      .sortBy(-_.length)
      .find(key => normalized.contains(key))
      .flatMap(pricingMap.get)

    val (inPrice, outPrice) = matched.getOrElse((0.0, 0.0))
    (inputTokens * inPrice + outputTokens * outPrice) / 1000000.0
