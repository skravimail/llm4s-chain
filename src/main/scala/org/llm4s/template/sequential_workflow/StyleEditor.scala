package org.llm4s.template.sequential_workflow

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.{ UserMessage, V }

trait StyleEditor {

  @Agent(outputKey = "story", description = "Edits a story to better fit a given style")
  @UserMessage(
    Array(
      "You are a professional editor.",
      "Analyze and rewrite the following story to better fit and be more coherent with the {{style}} style.",
      "Return only the story and nothing else.",
      "The story is \"{{story}}\".",
    )
  )
  def editStory(@V("story") story: String, @V("style") style: String): String
}