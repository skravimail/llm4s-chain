package org.l4j.template.demo

import cats.effect.IO
import cats.effect.Resource
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig
import org.l4j.template.llm4s.openai.SttpOpenAiTransport
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Uri

object BackendSupport:

  def fromEnv: Resource[IO, ChatBackend[IO]] =
    val baseUrl   = sys.env.getOrElse("LLM4S_BASE_URL", sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1"))
    val apiKey    = sys.env.getOrElse("LLM4S_API_KEY", sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OPENAI_API_KEY", "4850")))
    val modelName = sys.env.getOrElse("LLM4S_MODEL", sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit"))

    AsyncHttpClientCatsBackend.resource[IO]().map { sttp =>
      val transport = SttpOpenAiTransport[IO](
        baseUri = Uri.unsafeParse(baseUrl),
        backend = sttp,
      )
      OpenAiCompatBackend[IO](
        OpenAiCompatConfig(
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = modelName,
        ),
        transport,
      )
    }
