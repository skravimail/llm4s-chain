package org.l4j.template.demo

import cats.effect.IO
import cats.effect.Resource
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig
import org.l4j.template.llm4s.openai.SttpOpenAiTransport
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Uri

/** Builds the demo backend from `config.yaml` (PR-22) and wires the
  * `logging:` / `tracing:` sections (PR-23) so every demo respects the
  * same observability switches.
  *
  * The two factories share the same plumbing; pick based on whether the
  * caller wants the listener bundle returned:
  *
  *   - [[fromEnv]]    -> just the backend (back-compat shape; bundle is
  *                       still wired to `tracing.http`).
  *   - [[fromConfig]] -> backend + bundle + parsed config so the caller
  *                       can pass `bundle.runtime` to `AiAgent`. */
object BackendSupport:

  /** Backend only — `tracing.http` and `logging:` are honoured, but the
    * caller has no way to pass `bundle.runtime` into their `AiAgent`, so
    * chat / provider / tool events still fall through to the agent's
    * default noop listener. Suitable for demos that only care about HTTP
    * tracing or have no agent (raw `ChatBackend` use). */
  def fromEnv: Resource[IO, ChatBackend[IO]] =
    fromConfig.map(_._1)

  /** Backend + listener bundle + parsed config. The bundle's HTTP listener
    * is already wired into the transport; the caller is responsible for
    * threading `bundle.runtime` into their `AiAgent` (and
    * `bundle.guardrails` / `bundle.workflow` if applicable) so the rest of
    * the tracing config takes effect. */
  def fromConfig: Resource[IO, (ChatBackend[IO], ListenerBundle[IO], AppConfig)] =
    Resource.eval(IO {
      val app = AppConfig.load()
      app.logging.apply()
      val bundle = TracingWiring.buildListeners[IO](app.tracing, IO.println(_))
      (app, bundle)
    }).flatMap { case (app, bundle) =>
      AsyncHttpClientCatsBackend
        .resourceUsingConfigBuilder[IO](updateConfig = _
          .setRequestTimeout(app.llm.requestTimeout.toMillis.toInt)
          .setReadTimeout(app.llm.requestTimeout.toMillis.toInt))
        .map { sttpBackend =>
          val transport = SttpOpenAiTransport[IO](
            Uri.unsafeParse(app.llm.baseUrl),
            sttpBackend,
            bundle.http,
          )
          val backend = OpenAiCompatBackend[IO](
            OpenAiCompatConfig(
              baseUrl = app.llm.baseUrl,
              apiKey = app.llm.apiKey,
              model = app.llm.model,
              responseFormatMode = app.llm.responseFormatMode,
              requestTimeout = app.llm.requestTimeout,
            ),
            transport,
          )
          (backend, bundle, app)
        }
    }
