package org.llm4s.template

import munit.FunSuite
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, LLMError,
  StreamedChunk, TokenUsage, ValidationError }

/** This code is part of the Giter8 template llm4s.g8 in llm4s project, which provides a set
 * standard template/archetype for improving developer onboarding and creating new projects using
 * the llm4s library.
 */
class PromptExecutorSpec extends FunSuite {

  // Live-call tests hit a real LLM; local OMLX inference can take ~1 min.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(2, scala.concurrent.duration.MINUTES)

  test("basic assertion") {
    assertEquals(1 + 1, 2)
  }

  test("PromptExecutor returns a non-empty response from the configured provider") {
    val prompt   = "Explain what a Monad is in Scala"
    val response = PromptExecutor.run(prompt)
    assert(response.nonEmpty)
  }

  test("Mocked client: successful completion returns expected message") {
    val mockClient = new LLMClient {
      override def complete(
                             conversation: Conversation,
                             options: CompletionOptions,
                           ): Either[LLMError, Completion] = {
        val msg   = AssistantMessage(content = "Monads represent computations", toolCalls = Nil)
        val usage = Some(TokenUsage(10, 5, 5))
        Right(
          Completion(id = "id", created = System.currentTimeMillis(), message = msg, usage = usage)
        )
      }

      override def streamComplete(
                                   conversation: Conversation,
                                   options: CompletionOptions,
                                   onChunk: StreamedChunk => Unit,
                                 ): Either[LLMError, Completion] =
        Left(ValidationError("Streaming not supported"))
    }

    val result = PromptExecutor.run("Explain monads", Some(mockClient))
    assertEquals(result, "Monads represent computations")
  }

  test("Mocked client: missing completion returns fallback error message") {
    val mockClient = new LLMClient {
      override def complete(
                             conversation: Conversation,
                             options: CompletionOptions,
                           ): Either[LLMError, Completion] =
        Left(ValidationError("No completion available"))

      override def streamComplete(
                                   conversation: Conversation,
                                   options: CompletionOptions,
                                   onChunk: StreamedChunk => Unit,
                                 ): Either[LLMError, Completion] =
        Left(ValidationError("Streaming not supported"))
    }

    val result = PromptExecutor.run("This should fail", Some(mockClient))
    assert(result.contains("No completion available"))
  }

  test("Mocked client: error handling returns appropriate message") {
    val mockClient = new LLMClient {
      override def complete(
                             conversation: Conversation,
                             options: CompletionOptions,
                           ): Either[LLMError, Completion] =
        Left(ValidationError("LLM is down"))

      override def streamComplete(
                                   conversation: Conversation,
                                   options: CompletionOptions,
                                   onChunk: StreamedChunk => Unit,
                                 ): Either[LLMError, Completion] =
        Left(ValidationError("Streaming not supported"))
    }

    val result = PromptExecutor.run("Trigger error", Some(mockClient))
    assert(result.contains("LLM is down"))
  }
}
