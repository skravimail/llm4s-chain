package org.llm4s.template.sequential_workflow

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.{ UserMessage, V }

trait AudienceEditor {

  @Agent(outputKey = "story", description = "Edits a story to better fit a given audience")
  @UserMessage(
    Array(
      "You are a professional editor.",
      "Analyze and rewrite the following story to better align",
      "with the target audience of {{audience}}.",
      "Return only the story and nothing else.",
      "The story is \"{{story}}\".",
    )
  )
  def editStory(@V("story") story: String, @V("audience") audience: String): String
}