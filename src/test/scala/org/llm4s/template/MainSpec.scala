package org.llm4s.template

/**
 * This code is part of the Giter8 template llm4s.g8 in llm4s project, which provides a set standard template/archetype
 * for improve developer onboarding, creating new projects using the llm4s library.
 */

/** The MainSpec class contains unit tests for the PromptExecutor functionality.
 * It checks basic assertions and the response from the LLM when a prompt is executed.
 */
class MainSpec extends munit.FunSuite {

  // Live-call tests hit a real LLM; local OMLX inference can take ~1 min.
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(2, scala.concurrent.duration.MINUTES)

  test("basic assertion") {
    assert(1 + 1 == 2)
  }

  test("Test Prompt executor") {
    val prompt = "Explain what a Monad is in Scala"
    val response = PromptExecutor.run(prompt)
    assert(response.nonEmpty, "Response should not be empty")
  }
}
