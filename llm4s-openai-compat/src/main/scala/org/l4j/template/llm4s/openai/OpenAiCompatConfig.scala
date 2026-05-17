package org.l4j.template.llm4s.openai

/** How an `OpenAiCompat` backend emits the OpenAI `response_format` field.
  *
  * The OpenAI Chat Completions API evolved this field twice — older servers
  * (and some local OpenAI-compatible runtimes like osaurus, older LM Studio,
  * llama.cpp's HTTP server) only implement subsets. PR-18.
  *
  *   - [[JsonSchema]] (default): the strict, schema-enforced shape introduced
  *     by OpenAI in 2024 — `{"type":"json_schema","json_schema":{...}}`. The
  *     server validates the model's output against the schema. Required for
  *     real `chatAs[T]` reliability.
  *   - [[JsonObject]]: the older `{"type":"json_object"}` form. Server forces
  *     valid JSON but doesn't enforce a schema; rely on the system prompt
  *     plus the library's own decoder to validate fields.
  *   - [[Disabled]]: omit `response_format` entirely. Use when the server
  *     rejects the field outright or you want to send unconstrained text and
  *     parse JSON manually.
  */
enum ResponseFormatMode:
  case JsonSchema
  case JsonObject
  case Disabled

import scala.concurrent.duration.*

final case class OpenAiCompatConfig(
    baseUrl: String,
    apiKey: String,
    model: String,
    defaultHeaders: Map[String, String] = Map.empty,
    responseFormatMode: ResponseFormatMode = ResponseFormatMode.JsonSchema,
    /** Per-request HTTP timeout passed down to the sttp `AsyncHttpClient`.
      * Defaults to 60s (the sttp default). Bump this for slow local models
      * — a small gemma running structured output over a multi-paragraph
      * prompt can easily exceed 60s on consumer hardware (PR-19). */
    requestTimeout: FiniteDuration = 60.seconds,
)
