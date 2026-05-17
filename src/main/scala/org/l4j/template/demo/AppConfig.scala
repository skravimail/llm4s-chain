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

/** Parsed `config.yaml` `llm:` section. */
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

/** Per-component tracing verbosity.
  *
  *   - `Off`   – no listener wired (zero overhead).
  *   - `Info`  – lifecycle events only: chat / provider / tool start+end with
  *               timings and counts, never bodies.
  *   - `Debug` – everything Info shows, plus request / response payloads,
  *               full tool arguments, full tool results. */
enum TraceLevel:
  case Off, Info, Debug

object TraceLevel:
  def parse(raw: String): Either[String, TraceLevel] =
    raw.trim.toLowerCase match
      case "off" | "false" | "none" => Right(Off)
      case "info" | "true" | "on"   => Right(Info)
      case "debug" | "verbose"      => Right(Debug)
      case other =>
        Left(s"unknown trace level '$other'; expected off | info | debug")

/** Per-component tracing configuration. Each field controls whether (and
  * how loud) the matching listener is wired into the runtime. */
final case class TracingConfig(
    runtime: TraceLevel,
    http: TraceLevel,
    guardrails: TraceLevel,
    workflow: TraceLevel,
)

object TracingConfig:
  val default: TracingConfig =
    TracingConfig(TraceLevel.Off, TraceLevel.Off, TraceLevel.Off, TraceLevel.Off)

/** `logging:` section. `root` is the default level for unconfigured loggers
  * (passed straight to logback's root logger). `loggers` is a name → level
  * map for tuning specific libraries (e.g. `sttp.client3 -> warn`). Levels
  * follow logback conventions: trace | debug | info | warn | error | off. */
final case class LoggingConfig(
    root: String,
    loggers: Map[String, String],
):
  /** Apply these levels to the currently-installed logback context (no-op
    * if SLF4J is bound to something other than logback). Safe to call more
    * than once. */
  def apply(): Unit =
    val factory = org.slf4j.LoggerFactory.getILoggerFactory
    factory match
      case ctx: ch.qos.logback.classic.LoggerContext =>
        val rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        rootLogger.setLevel(ch.qos.logback.classic.Level.toLevel(root))
        loggers.foreach { case (name, level) =>
          ctx.getLogger(name).setLevel(ch.qos.logback.classic.Level.toLevel(level))
        }
      case _ => () // not logback — silently ignore

object LoggingConfig:
  val default: LoggingConfig = LoggingConfig("info", Map.empty)

  private val ValidLevels: Set[String] =
    Set("trace", "debug", "info", "warn", "error", "off")

  /** Validate a level string against logback's accepted values; throws
    * with a clear message on garbage so misconfig surfaces at startup
    * (logback's `Level.toLevel` defaults silently to DEBUG which is
    * worse than failing loudly). */
  def validateLevel(raw: String, path: String): Unit =
    if !ValidLevels.contains(raw.trim.toLowerCase) then
      throw IllegalArgumentException(
        s"$path='$raw' is not a valid log level; expected one of ${ValidLevels.toList.sorted.mkString(" | ")}"
      )

final case class AppConfig(
    llm: LlmConfig,
    logging: LoggingConfig = LoggingConfig.default,
    tracing: TracingConfig = TracingConfig.default,
)

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

    val logging = Option(root.get("logging")) match
      case None => LoggingConfig.default
      case Some(raw) =>
        val map = asMap(raw, "logging")
        val rootLevel = Option(map.get("root")).map(_.toString).getOrElse("info")
        LoggingConfig.validateLevel(rootLevel, "logging.root")
        val loggers = Option(map.get("loggers")) match
          case None => Map.empty[String, String]
          case Some(lmap) =>
            val m = asMap(lmap, "logging.loggers")
            m.asScala.iterator.map { case (k, v) =>
              val level = v.toString
              LoggingConfig.validateLevel(level, s"logging.loggers.$k")
              k -> level
            }.toMap
        LoggingConfig(rootLevel, loggers)

    val tracing = Option(root.get("tracing")) match
      case None => TracingConfig.default
      case Some(raw) =>
        val map = asMap(raw, "tracing")
        def readLevel(key: String): TraceLevel =
          Option(map.get(key)) match
            case None => TraceLevel.Off
            case Some(v) =>
              TraceLevel.parse(v.toString) match
                case Right(l) => l
                case Left(m)  => throw IllegalArgumentException(s"tracing.$key: $m")
        TracingConfig(
          runtime = readLevel("runtime"),
          http = readLevel("http"),
          guardrails = readLevel("guardrails"),
          workflow = readLevel("workflow"),
        )

    AppConfig(
      llm = LlmConfig(
        provider = provider,
        model = model,
        baseUrlOverride = baseUrlOverride,
        responseFormatMode = responseFormatMode,
        requestTimeout = requestTimeout,
      ),
      logging = logging,
      tracing = tracing,
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
