package org.llm4s.template.l4j_scala

import dev.langchain4j.data.message.{ AiMessage, ChatMessage, SystemMessage => JSystemMessage, UserMessage => JUserMessage }
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse

import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.jdk.CollectionConverters._

/**
 * Scala-friendly entry points for langchain4j's `ChatModel`.
 *
 * `chatAsync` runs the underlying blocking call on the supplied ExecutionContext, wrapped
 * in `scala.concurrent.blocking` so a managed pool can grow as needed.
 */
object Chat {

  /** Smart constructors for the message types you'll actually hand to a model. */
  object Msg {
    def system(content: String): JSystemMessage = JSystemMessage.from(content)
    def user(content: String): JUserMessage     = JUserMessage.from(content)
    def assistant(content: String): AiMessage   = AiMessage.from(content)
  }

  /**
   * Build a `ChatRequest` from a Scala sequence of messages plus optional sampling params.
   * Pass-throughs are `Option`-shaped so callers don't juggle null/Optional.
   */
  final case class RequestSpec(
    messages: Seq[ChatMessage],
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    maxOutputTokens: Option[Int] = None,
    stopSequences: Seq[String] = Seq.empty,
  ) {
    def toJava: ChatRequest = {
      val b = ChatRequest.builder().messages(messages.asJava)
      temperature.foreach(v => b.temperature(java.lang.Double.valueOf(v)))
      topP.foreach(v => b.topP(java.lang.Double.valueOf(v)))
      maxOutputTokens.foreach(v => b.maxOutputTokens(java.lang.Integer.valueOf(v)))
      if (stopSequences.nonEmpty) b.stopSequences(stopSequences.asJava)
      b.build()
    }
  }

  implicit final class ChatModelOps(private val model: ChatModel) extends AnyVal {

    /** Async completion using a Scala message sequence. */
    def chatAsync(messages: Seq[ChatMessage])(implicit ec: ExecutionContext): Future[ChatResponse] =
      Future(blocking(model.chat(messages.asJava)))

    /** Async completion using a typed request spec (temperature/topP/etc.). */
    def chatAsync(spec: RequestSpec)(implicit ec: ExecutionContext): Future[ChatResponse] =
      Future(blocking(model.chat(spec.toJava)))

    /** Convenience: send a single user prompt, get back the assistant text. */
    def askAsync(prompt: String)(implicit ec: ExecutionContext): Future[String] =
      chatAsync(Seq(Msg.user(prompt))).map(_.aiMessage().text())
  }
}
