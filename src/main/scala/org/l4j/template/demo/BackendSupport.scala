package org.l4j.template.demo

import cats.effect.IO
import cats.effect.Resource
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig
import org.l4j.template.llm4s.openai.ResponseFormatMode
import scala.concurrent.duration.*

object BackendSupport:

  def fromEnv: Resource[IO, ChatBackend[IO]] =
    val baseUrl   = sys.env.getOrElse("LLM4S_BASE_URL", sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1"))
    val apiKey    = sys.env.getOrElse(
      "LLM4S_API_KEY",
      sys.env.getOrElse(
        "LANGCHAIN4J_API_KEY",
        sys.env.getOrElse(
          "OPENAI_API_KEY",
          sys.env.getOrElse("GOOGLE_API_KEY", "4850"),
        ),
      ),
    )
    val modelName = sys.env.getOrElse("LLM4S_MODEL", sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit"))
    val responseFormatMode = parseResponseFormatMode(
      sys.env.getOrElse("LLM4S_RESPONSE_FORMAT_MODE", "json_schema")
    )
    val requestTimeout = parseTimeoutSeconds(
      sys.env.getOrElse("LLM4S_REQUEST_TIMEOUT_SECONDS", "60")
    )

    OpenAiCompatBackend.resource[IO](
      OpenAiCompatConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = modelName,
        responseFormatMode = responseFormatMode,
        requestTimeout = requestTimeout,
      )
    )

  /** Parse `LLM4S_REQUEST_TIMEOUT_SECONDS` into a `FiniteDuration`. Local
    * models generating structured output frequently need 2–5× the sttp 60s
    * default; this env knob avoids editing code to bump it (PR-19). */
  private def parseTimeoutSeconds(raw: String): FiniteDuration =
    raw.trim.toIntOption match
      case Some(n) if n > 0 => n.seconds
      case _ =>
        throw IllegalArgumentException(
          s"Invalid LLM4S_REQUEST_TIMEOUT_SECONDS='$raw'; expected a positive integer"
        )

  /** Parse `LLM4S_RESPONSE_FORMAT_MODE`. Defaults to JsonSchema (the strict
    * shape) but adopters running against older servers (osaurus, older LM
    * Studio, llama.cpp) should set `LLM4S_RESPONSE_FORMAT_MODE=json_object`
    * or `disabled`. See `ResponseFormatMode` scaladoc (PR-18). */
  private def parseResponseFormatMode(raw: String): ResponseFormatMode =
    raw.trim.toLowerCase match
      case "json_schema" | "jsonschema" => ResponseFormatMode.JsonSchema
      case "json_object" | "jsonobject" => ResponseFormatMode.JsonObject
      case "disabled" | "none" | "off"  => ResponseFormatMode.Disabled
      case other =>
        throw IllegalArgumentException(
          s"Unknown LLM4S_RESPONSE_FORMAT_MODE='$other'; expected json_schema | json_object | disabled"
        )
