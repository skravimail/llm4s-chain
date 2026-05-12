package org.l4j.template.llm4s.rag

import cats.Monad
import cats.syntax.all.*
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest

final case class AugmentedChatRequest(
    request: ChatRequest,
    sources: List[RetrievedSource],
)

trait RetrievalAugmentor[F[_]]:
  def augment(request: ChatRequest): F[AugmentedChatRequest]

final class DefaultRetrievalAugmentor[F[_]: Monad](
    retriever: ContentRetriever[F],
    renderContext: List[RetrievedSource] => String = DefaultRetrievalAugmentor.renderContext,
) extends RetrievalAugmentor[F]:

  override def augment(request: ChatRequest): F[AugmentedChatRequest] =
    lastUserMessage(request.messages) match
      case None =>
        AugmentedChatRequest(request, Nil).pure[F]
      case Some((index, userMessage)) =>
        val query = userMessage.text.trim
        if query.isEmpty then AugmentedChatRequest(request, Nil).pure[F]
        else
          retriever.retrieve(query).map { sources =>
            if sources.isEmpty then AugmentedChatRequest(request, Nil)
            else
              val augmentedMessage = userMessage.copy(
                contents = List(AiContent.Text(renderUserMessage(query, sources)))
              )
              AugmentedChatRequest(
                request = request.copy(messages = request.messages.updated(index, augmentedMessage)),
                sources = sources,
              )
          }

  private def lastUserMessage(messages: List[ChatMessage]): Option[(Int, ChatMessage.UserMessage)] =
    messages.zipWithIndex.reverse.collectFirst { case (message: ChatMessage.UserMessage, index) =>
      index -> message
    }

  private def renderUserMessage(query: String, sources: List[RetrievedSource]): String =
    val context = renderContext(sources)
    s"Use the following retrieved context when it is relevant.\n\n$context\n\nUser request:\n$query"

object DefaultRetrievalAugmentor:
  def renderContext(sources: List[RetrievedSource]): String =
    sources
      .map(source => s"[source:${source.id} score:${"%.4f".format(source.score)}]\n${source.text}")
      .mkString("\n\n")
