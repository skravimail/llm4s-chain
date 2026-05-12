package org.l4j.template.llm4s.openai

final case class OpenAiCompatConfig(
    baseUrl: String,
    apiKey: String,
    model: String,
    defaultHeaders: Map[String, String] = Map.empty,
)
