package org.llm4s.template

import org.yaml.snakeyaml.Yaml

import java.io.InputStream
import scala.jdk.CollectionConverters._

/**
 * Loads LLM configuration from a YAML resource (default: `llm-config.yaml` on the classpath).
 *
 * The YAML carries non-secret settings (provider, model, baseUrl). The API key is always
 * resolved from the environment variable named by `apiKeyEnv` so secrets stay out of the repo.
 */
final case class LLMConfig(
  provider: String,
  model: String,
  baseUrl: String,
  apiKey: String,
)

object LLMConfig {
  private val DefaultResource = "llm-config.yaml"

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

      val provider   = requireStr("provider")
      val model      = requireStr("model")
      val apiKeyEnv  = requireStr("apiKeyEnv")
      val baseUrlYml = requireStr("baseUrl")
      val baseUrlEnv = llm.get("baseUrlEnv").map(_.toString).filter(_.nonEmpty)

      val baseUrl = baseUrlEnv.flatMap(sys.env.get).filter(_.nonEmpty).getOrElse(baseUrlYml)
      val apiKey  = sys.env.getOrElse(apiKeyEnv, "your-api-key-here")

      LLMConfig(provider = provider, model = model, baseUrl = baseUrl, apiKey = apiKey)
    } finally stream.close()
  }
}
