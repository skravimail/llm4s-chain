package org.l4j.template.demo

import cats.effect.IO
import cats.effect.Resource
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig

object BackendSupport:

  /** Build a backend from `config.yaml` (provider/model/etc) + `.env`
    * (API keys). PR-22 — previously this read everything from env vars
    * directly.
    */
  def fromEnv: Resource[IO, ChatBackend[IO]] =
    val app = AppConfig.load()
    OpenAiCompatBackend.resource[IO](
      OpenAiCompatConfig(
        baseUrl = app.llm.baseUrl,
        apiKey = app.llm.apiKey,
        model = app.llm.model,
        responseFormatMode = app.llm.responseFormatMode,
        requestTimeout = app.llm.requestTimeout,
      )
    )
