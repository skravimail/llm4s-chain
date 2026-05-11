package org.llm4s.template

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._

import scala.util.{ Failure, Success, Try }

/**
 * Plain-HTTP OpenAI-compatible client for OMLX (or any local OpenAI-compatible server).
 *
 * llm4s's bundled OpenAIClient sits on the Azure OpenAI SDK, whose KeyCredentialPolicy
 * rejects plaintext HTTP. This client bypasses that to make `http://localhost:...` work.
 */
final class OmlxClient(config: LLMConfig) extends LLMClient {
  private val endpoint = config.baseUrl.stripSuffix("/") + "/chat/completions"

  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
  ): Either[LLMError, Completion] = {
    val messages = ujson.Arr.from(conversation.messages.map { m =>
      ujson.Obj("role" -> m.role, "content" -> m.content)
    })

    val body = ujson.Obj(
      "model"             -> config.model,
      "messages"          -> messages,
      "temperature"       -> options.temperature,
      "top_p"             -> options.topP,
      "presence_penalty"  -> options.presencePenalty,
      "frequency_penalty" -> options.frequencyPenalty,
    )
    options.maxTokens.foreach(mt => body("max_tokens") = mt)

    Try(
      requests.post(
        endpoint,
        data = body.render(),
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json",
        ),
        check = false,
      )
    ) match {
      case Success(resp) if resp.statusCode == 200 =>
        parseCompletion(resp.text())
      case Success(resp) if resp.statusCode == 401 || resp.statusCode == 403 =>
        Left(AuthenticationError(s"OMLX HTTP ${resp.statusCode}: ${resp.text()}"))
      case Success(resp) if resp.statusCode == 429 =>
        Left(RateLimitError(s"OMLX HTTP 429: ${resp.text()}"))
      case Success(resp) =>
        Left(ServiceError(s"OMLX HTTP ${resp.statusCode}: ${resp.text()}", resp.statusCode))
      case Failure(e) =>
        Left(UnknownError(e))
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit,
  ): Either[LLMError, Completion] = complete(conversation, options)

  private def parseCompletion(raw: String): Either[LLMError, Completion] =
    Try {
      val json   = ujson.read(raw)
      val choice = json("choices")(0)
      val msg    = choice("message")
      val usage = json.obj.get("usage").map { u =>
        TokenUsage(
          promptTokens     = u("prompt_tokens").num.toInt,
          completionTokens = u("completion_tokens").num.toInt,
          totalTokens      = u("total_tokens").num.toInt,
        )
      }
      Completion(
        id      = json.obj.get("id").map(_.str).getOrElse(""),
        created = json.obj.get("created").map(_.num.toLong).getOrElse(System.currentTimeMillis / 1000),
        message = AssistantMessage(content = msg("content").str),
        usage   = usage,
      )
    }.toEither.left.map(e => ValidationError(s"Could not parse OMLX response: ${e.getMessage}"))
}
