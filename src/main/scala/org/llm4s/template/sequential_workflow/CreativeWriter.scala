package org.llm4s.template.sequential_workflow

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.{ UserMessage, V }

trait CreativeWriter {

  @Agent(outputKey = "story", description = "Generates a story based on the given topic")
  @UserMessage(
    Array(
      "You are a creative writer.",
      "Generate a draft of a story no more than",
      "3 sentences long around the given topic.",
      "Return only the story and nothing else.",
      "The topic is {{topic}}.",
    )
  )
  def generateStory(@V("topic") topic: String): String
}