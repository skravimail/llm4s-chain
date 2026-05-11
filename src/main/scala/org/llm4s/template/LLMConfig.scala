package org.llm4s.template

import org.yaml.snakeyaml.Yaml

import java.io.InputStream
import scala.jdk.CollectionConverters._

/**
 * Loads LLM configuration from a YAML resource (default: `llm-config.yaml` on the classpath).
 *
 * Required keys: `llm.provider`, `llm.model`.
 * Optional keys: `llm.baseUrl` (falls back to a provider-aware default),
 *                `llm.apiKeyEnv` (name of env var holding the secret; empty key if omitted),
 *                `llm.baseUrlEnv` (env var that can override `baseUrl` at runtime).
 */
final case class LLMConfig(
  provider: String,
  model: String,
  baseUrl: String,
  apiKey: String,
)

object LLMConfig {
  private val DefaultResource = "llm-config.yaml"

  // Provider-aware default base URLs. Gemini exposes an OpenAI-compatible endpoint
  // at this path so the existing OpenAI client routing keeps working.
  private val DefaultBaseUrls: Map[String, String] = Map(
    "openai" -> "https://api.openai.com/v1",
    "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai",
  )

  def load(resource: String = DefaultResource): LLMConfig = {
    val stream: InputStream = Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(throw new IllegalStateException(s"LLM config resource not found on classpath: $resource"))

    try {
      val root = new Yaml().load[java.util.Map[String, Any]](stream)
      val llm  = Option(root.get("llm"))
        .collect { case m: java.util.Map[_, _] => m.asInstanceOf[java.util.Map[String, Any]].asScala.toMap }
        .getOrElse(throw new IllegalStateException(s"Missing top-level `llm:` block in $resource"))

      def requireStr(key: String): String =
        llm.get(key).map(_.toString).filter(_.nonEmpty)
          .getOrElse(throw new IllegalStateException(s"Missing `llm.$key` in $resource"))

      def optStr(key: String): Option[String] =
        llm.get(key).map(_.toString).filter(_.nonEmpty)

      val provider   = requireStr("provider")
      val model      = requireStr("model")
      val baseUrlYml = optStr("baseUrl")
      val baseUrlEnv = optStr("baseUrlEnv")
      val apiKeyEnv  = optStr("apiKeyEnv")

      val baseUrl = baseUrlEnv.flatMap(sys.env.get).filter(_.nonEmpty)
        .orElse(baseUrlYml)
        .orElse(DefaultBaseUrls.get(provider.toLowerCase))
        .getOrElse("")

      // The OpenAI client rejects an empty key at construction, so fall back to a
      // non-empty placeholder when no env var is configured. Real auth failures
      // surface as 401 at request time.
      val apiKey = apiKeyEnv.flatMap(sys.env.get).filter(_.nonEmpty).getOrElse("your-api-key-here")

      LLMConfig(provider = provider, model = model, baseUrl = baseUrl, apiKey = apiKey)
    } finally stream.close()
  }
}
