package org.llm4s.template

import com.typesafe.scalalogging.LazyLogging
import org.llm4s.llmconnect.{LLM, LLMClient}
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ Completion, Conversation, LLMError, SystemMessage, UserMessage }
import org.llm4s.llmconnect.provider.LLMProvider

/**
 * This code is part of the Giter8 template llm4s.g8 in llm4s project, which provides a set
 * standard template/archetype for improve developer onboarding, creating new projects using the
 * llm4s library.
 */

/**
 * The PromptExecutor object is responsible for executing prompts against the OpenAI LLM.
 * It initializes the LLM client with the necessary configuration and provides a method to run
 * prompts, returning the assistant's response or an error message.
 */

object PromptExecutor extends LazyLogging {
  // Load provider config from src/main/resources/llm-config.yaml.
  // API key is resolved from the env var named in the YAML (default: OPENAI_API_KEY).
  private val llmConfig: LLMConfig = LLMConfig.load()

  // Dispatch on `provider`. OMLX (and other local OpenAI-compat servers) uses a
  // plain-HTTP client; the Azure-SDK-backed llm4s OpenAIClient refuses plaintext HTTP.
  private val defaultClient: LLMClient = llmConfig.provider.toLowerCase match {
    case "omlx" =>
      new OmlxClient(llmConfig)
    case _ =>
      val openaiConfig = OpenAIConfig(
        apiKey = llmConfig.apiKey,
        model = llmConfig.model,
        baseUrl = llmConfig.baseUrl,
      )
      LLM.client(LLMProvider.OpenAI, openaiConfig)
  }

  def run(prompt: String, clientOpt: Option[LLMClient] = None): String = {
    val client = clientOpt.getOrElse(defaultClient)

    // Build a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage(prompt),
      )
    )

    // Perform synchronous completion
    val completion: Either[LLMError, Completion] = client.complete(conversation)

    completion match {
      case Right(comp) =>
        val completionMessage = comp.message.content
        logger.info("✅ Assistant response: " + completionMessage)
        completionMessage
      case Left(err) =>
        val errorMsg = err.message
        logger.error("❌ Error: " + errorMsg)
        errorMsg
    }
  }
}
