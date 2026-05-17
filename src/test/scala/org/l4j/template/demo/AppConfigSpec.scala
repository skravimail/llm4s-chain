package org.l4j.template.demo

import munit.FunSuite
import org.l4j.template.llm4s.openai.ResponseFormatMode
import scala.concurrent.duration.*

class AppConfigSpec extends FunSuite:

  test("minimal YAML: provider + model only, everything else defaults") {
    val yaml =
      """llm:
        |  provider: gemini
        |  model: gemini-2.5-flash
        |""".stripMargin
    val cfg = AppConfig.parse(yaml).llm
    assertEquals(cfg.provider, Provider.Gemini)
    assertEquals(cfg.model, "gemini-2.5-flash")
    assertEquals(cfg.responseFormatMode, ResponseFormatMode.JsonSchema)
    assertEquals(cfg.requestTimeout, 60.seconds)
    assertEquals(cfg.baseUrlOverride, None)
    assertEquals(cfg.baseUrl, "https://generativelanguage.googleapis.com/v1beta/openai")
  }

  test("full YAML overrides every optional field") {
    val yaml =
      """llm:
        |  provider: omlx
        |  model: gemma-4-e2b-it-8bit
        |  response_format_mode: json_object
        |  request_timeout_seconds: 300
        |  base_url: http://127.0.0.1:1337
        |""".stripMargin
    val cfg = AppConfig.parse(yaml).llm
    assertEquals(cfg.provider, Provider.Omlx)
    assertEquals(cfg.model, "gemma-4-e2b-it-8bit")
    assertEquals(cfg.responseFormatMode, ResponseFormatMode.JsonObject)
    assertEquals(cfg.requestTimeout, 300.seconds)
    assertEquals(cfg.baseUrlOverride, Some("http://127.0.0.1:1337"))
    assertEquals(cfg.baseUrl, "http://127.0.0.1:1337")
  }

  test("each provider knows its default base_url + key env-var") {
    assertEquals(Provider.Gemini.defaultBaseUrl, Some("https://generativelanguage.googleapis.com/v1beta/openai"))
    assertEquals(Provider.Gemini.apiKeyEnvVar, Some("GOOGLE_API_KEY"))

    assertEquals(Provider.OpenAi.defaultBaseUrl, Some("https://api.openai.com/v1"))
    assertEquals(Provider.OpenAi.apiKeyEnvVar, Some("OPENAI_API_KEY"))

    assertEquals(Provider.Ollama.defaultBaseUrl, Some("http://localhost:11434/v1"))
    assertEquals(Provider.Ollama.apiKeyEnvVar, None) // no auth

    assertEquals(Provider.Omlx.defaultBaseUrl, Some("http://localhost:1337"))
    assertEquals(Provider.Omlx.apiKeyEnvVar, Some("OMLX_API_KEY"))

    assertEquals(Provider.Ibm.defaultBaseUrl, None) // requires explicit override
    assertEquals(Provider.Ibm.apiKeyEnvVar, Some("IBM_API_KEY"))
  }

  test("Ibm without base_url override fails loudly when resolved") {
    val yaml =
      """llm:
        |  provider: ibm
        |  model: ibm/granite
        |""".stripMargin
    val cfg = AppConfig.parse(yaml).llm
    val ex = intercept[IllegalArgumentException](cfg.baseUrl)
    assert(ex.getMessage.contains("base_url"))
  }

  test("Ollama apiKey returns empty string (no auth required)") {
    val yaml =
      """llm:
        |  provider: ollama
        |  model: llama3.2
        |""".stripMargin
    val cfg = AppConfig.parse(yaml).llm
    // Should not throw; Ollama doesn't need a key.
    assertEquals(cfg.apiKey, "")
  }

  test("unknown provider rejected") {
    val yaml =
      """llm:
        |  provider: unknown-provider
        |  model: x
        |""".stripMargin
    val ex = intercept[IllegalArgumentException](AppConfig.parse(yaml))
    assert(ex.getMessage.contains("unknown provider"))
  }

  test("missing provider rejected") {
    val yaml =
      """llm:
        |  model: x
        |""".stripMargin
    val ex = intercept[IllegalArgumentException](AppConfig.parse(yaml))
    assert(ex.getMessage.contains("llm.provider"))
  }

  test("missing model rejected") {
    val yaml =
      """llm:
        |  provider: gemini
        |""".stripMargin
    val ex = intercept[IllegalArgumentException](AppConfig.parse(yaml))
    assert(ex.getMessage.contains("llm.model"))
  }

  test("missing llm section rejected") {
    val yaml = "other: stuff\n"
    val ex = intercept[IllegalArgumentException](AppConfig.parse(yaml))
    assert(ex.getMessage.contains("llm"))
  }

  test("non-integer request_timeout_seconds rejected with a clear message") {
    val yaml =
      """llm:
        |  provider: gemini
        |  model: g
        |  request_timeout_seconds: forever
        |""".stripMargin
    val ex = intercept[IllegalArgumentException](AppConfig.parse(yaml))
    assert(ex.getMessage.contains("positive integer"))
  }

  test("response_format_mode accepts the documented aliases") {
    def parseMode(raw: String): ResponseFormatMode =
      AppConfig.parse(
        s"""llm:
           |  provider: gemini
           |  model: g
           |  response_format_mode: $raw
           |""".stripMargin
      ).llm.responseFormatMode

    assertEquals(parseMode("json_schema"), ResponseFormatMode.JsonSchema)
    assertEquals(parseMode("jsonschema"), ResponseFormatMode.JsonSchema)
    assertEquals(parseMode("json_object"), ResponseFormatMode.JsonObject)
    assertEquals(parseMode("disabled"), ResponseFormatMode.Disabled)
    assertEquals(parseMode("none"), ResponseFormatMode.Disabled)
    assertEquals(parseMode("off"), ResponseFormatMode.Disabled)
  }
