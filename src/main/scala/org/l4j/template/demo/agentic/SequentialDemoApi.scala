package org.l4j.template.demo.agentic

import org.l4j.template.llm4s.macros.system
import org.l4j.template.llm4s.macros.user

trait CreativeWriter:
  @system("You are a creative writer.")
  @user(
    "Generate a draft of a story no more than 3 sentences long around the given topic. " +
      "Return only the story and nothing else. The topic is {{topic}}."
  )
  def generateStory(topic: String): String

trait AudienceEditor:
  @system("You are a professional editor.")
  @user(
    "Analyze and rewrite the following story to better align with the target audience of {{audience}}. " +
      "Return only the story and nothing else. The story is \"{{story}}\"."
  )
  def editStory(story: String, audience: String): String

trait StyleEditor:
  @system("You are a professional editor.")
  @user(
    "Analyze and rewrite the following story to better fit and be more coherent with the {{style}} style. " +
      "Return only the story and nothing else. The story is \"{{story}}\"."
  )
  def editStory(story: String, style: String): String

final case class StoryDraft(
    topic: String,
    audience: String,
    style: String,
    story: String,
)
