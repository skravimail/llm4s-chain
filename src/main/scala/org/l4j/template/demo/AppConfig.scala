package org.l4j.template.demo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.l4j.template.llm4s.openai.ResponseFormatMode
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Provider-neutral facade over the OpenAI-compatible endpoints we know
  * about. Each variant carries the default base URL and the conventional
  * env-var to look up for the API key, so the YAML only needs to name the
  * provider (PR-22). */
enum Provider(val defaultBaseUrl: Option[String], val apiKeyEnvVar: Option[String]):
  case Gemini extends Provider(
    Some("https://generativelanguage.googleapis.com/v1beta/openai"),
    Some("GOOGLE_API_KEY"),
  )
  case OpenAi extends Provider(
    Some("https://api.openai.com/v1"),
    Some("OPENAI_API_KEY"),
  )
  case Ibm extends Provider(
    None, // watsonx endpoints vary by region; force explicit base_url
    Some("IBM_API_KEY"),
  )
  case Ollama extends Provider(
    Some("http://localhost:11434/v1"),
    None, // local Ollama doesn't require auth
  )
  case Omlx extends Provider(
    Some("http://localhost:1337"),
    Some("OMLX_API_KEY"),
  )

object Provider:
  def parse(raw: String): Either[String, Provider] =
    raw.trim.toLowerCase match
      case "gemini" => Right(Gemini)
      case "openai" => Right(OpenAi)
      case "ibm"    => Right(Ibm)
      case "ollama" => Right(Ollama)
      case "omlx"   => Right(Omlx)
      case other    => Left(s"unknown provider '$other'; expected one of gemini | openai | ibm | ollama | omlx")

/** Parsed `config.yaml`. */
final case class LlmConfig(
    provider: Provider,
    model: String,
    baseUrlOverride: Option[String],
    responseFormatMode: ResponseFormatMode,
    requestTimeout: FiniteDuration,
):
  /** Resolved base URL — explicit override wins; otherwise provider default
    * (errors if both are missing, as for `Provider.Ibm` without an override). */
  def baseUrl: String =
    baseUrlOverride
      .orElse(provider.defaultBaseUrl)
      .getOrElse(
        throw IllegalArgumentException(
          s"Provider $provider has no default base_url; set llm.base_url in config.yaml"
        )
      )

  /** Resolved API key. Reads the provider's conventional env var (env var
    * name can be overridden via the optional `apiKeyEnvVar` argument). For
    * providers that don't require auth (Ollama) returns an empty string. */
  def apiKey: String =
    provider.apiKeyEnvVar match
      case None         => ""
      case Some(envVar) =>
        sys.env.getOrElse(
          envVar,
          throw IllegalArgumentException(
            s"Provider ${provider.toString.toLowerCase} requires an API key in env var $envVar; add it to .env"
          ),
        )

final case class AppConfig(llm: LlmConfig)

object AppConfig:

  /** Load `config.yaml` from the working directory, walking upward until
    * one is found (so the demo works whether sbt is run at the repo root
    * or from inside a sub-project). */
  def load(): AppConfig =
    val path = locate("config.yaml").getOrElse(
      throw IllegalArgumentException("config.yaml not found in working directory or any parent")
    )
    val raw = Files.readString(path)
    parse(raw)

  /** Parse a YAML string. Public so tests can exercise it without touching
    * the filesystem. */
  def parse(yaml: String): AppConfig =
    val loaded = Load(LoadSettings.builder().build()).loadFromString(yaml)
    val root = asMap(loaded, "<root>")
    val llmRaw = asMap(root.get("llm"), "llm")

    val provider = Provider.parse(asString(llmRaw.get("provider"), "llm.provider")) match
      case Right(p) => p
      case Left(m)  => throw IllegalArgumentException(m)

    val model = asString(llmRaw.get("model"), "llm.model")

    val baseUrlOverride = Option(llmRaw.get("base_url")).map(_.toString)

    val responseFormatMode = Option(llmRaw.get("response_format_mode")) match
      case None => ResponseFormatMode.JsonSchema
      case Some(raw) =>
        raw.toString.trim.toLowerCase match
          case "json_schema" | "jsonschema" => ResponseFormatMode.JsonSchema
          case "json_object" | "jsonobject" => ResponseFormatMode.JsonObject
          case "disabled" | "none" | "off"  => ResponseFormatMode.Disabled
          case other =>
            throw IllegalArgumentException(
              s"Unknown llm.response_format_mode='$other'; expected json_schema | json_object | disabled"
            )

    val requestTimeout = Option(llmRaw.get("request_timeout_seconds")) match
      case None        => 60.seconds
      case Some(value) =>
        val n = value.toString.toIntOption.getOrElse(
          throw IllegalArgumentException(
            s"llm.request_timeout_seconds must be a positive integer; got '$value'"
          )
        )
        if n <= 0 then
          throw IllegalArgumentException(
            s"llm.request_timeout_seconds must be > 0; got $n"
          )
        n.seconds

    AppConfig(
      LlmConfig(
        provider = provider,
        model = model,
        baseUrlOverride = baseUrlOverride,
        responseFormatMode = responseFormatMode,
        requestTimeout = requestTimeout,
      )
    )

  private def asMap(v: Any | Null, path: String): java.util.Map[String, Any] =
    v match
      case m: java.util.Map[?, ?] => m.asInstanceOf[java.util.Map[String, Any]]
      case null => throw IllegalArgumentException(s"$path is missing in config.yaml")
      case other => throw IllegalArgumentException(s"$path must be a mapping; got ${other.getClass.getSimpleName}")

  private def asString(v: Any | Null, path: String): String =
    v match
      case null => throw IllegalArgumentException(s"$path is required in config.yaml")
      case s    => s.toString

  /** Walk up from `Paths.get("").toAbsolutePath` looking for `name`. */
  private def locate(name: String): Option[Path] =
    val cwd = Paths.get("").toAbsolutePath
    LazyList.iterate(cwd)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(name))
      .find(Files.exists(_))
