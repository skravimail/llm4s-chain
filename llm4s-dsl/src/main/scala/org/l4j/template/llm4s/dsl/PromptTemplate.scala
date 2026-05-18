package org.l4j.template.llm4s.dsl

import cats.Applicative
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.rag.DefaultRetrievalAugmentor
import org.l4j.template.llm4s.rag.RetrievedSource

object PromptTemplate:
  def user[F[_]: Applicative, In](
      system: Option[String] = None,
      responseFormat: Option[ResponseFormat] = None,
      temperature: Option[Double] = None,
      metadata: Map[String, String] = Map.empty,
  )(renderUser: In => String): Runnable[F, In, ChatRequest] =
    Runnable.leaf("prompt-template") { (input, _) =>
      Applicative[F].pure(
        ChatRequest(
          messages =
            system.map(ChatMessage.SystemMessage.from).toList :+
              ChatMessage.UserMessage.from(renderUser(input)),
          responseFormat = responseFormat,
          temperature = temperature,
          metadata = metadata,
        )
      )
    }

  def retrievalAugmentedUser[F[_]: Applicative, In](
      system: Option[String] = None,
      responseFormat: Option[ResponseFormat] = None,
      temperature: Option[Double] = None,
      metadata: Map[String, String] = Map.empty,
      renderContext: List[RetrievedSource] => String = DefaultRetrievalAugmentor.renderContext,
  )(
      renderUser: In => String,
      renderSources: In => List[RetrievedSource],
  ): Runnable[F, In, ChatRequest] =
    Runnable.leaf("retrieval-prompt-template") { (input, _) =>
      val query = renderUser(input)
      val sources = renderSources(input)
      val userText =
        if sources.isEmpty then query
        else
          val context = renderContext(sources)
          s"Use the following retrieved context when it is relevant.\n\n$context\n\nUser request:\n$query"

      Applicative[F].pure(
        ChatRequest(
          messages =
            system.map(ChatMessage.SystemMessage.from).toList :+
              ChatMessage.UserMessage.from(userText),
          responseFormat = responseFormat,
          temperature = temperature,
          metadata = metadata,
        )
      )
    }
