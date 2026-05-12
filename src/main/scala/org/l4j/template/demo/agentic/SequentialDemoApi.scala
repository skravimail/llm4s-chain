package org.l4j.template.demo.agentic

import cats.effect.IO
import org.l4j.template.llm4s.structured.AiAgent

final case class StoryDraft(
    topic: String,
    audience: String,
    style: String,
    story: String,
)

object SequentialDemoApi:

  private val writerSystem   = "You are a creative writer."
  private val editorSystem   = "You are a professional editor."

  def generateStory(agent: AiAgent[IO], topic: String): IO[String] =
    agent.chat(
      system = writerSystem,
      user =
        s"Generate a draft of a story no more than 3 sentences long around the given topic. " +
          s"Return only the story and nothing else. The topic is $topic.",
    )

  def editForAudience(agent: AiAgent[IO], story: String, audience: String): IO[String] =
    agent.chat(
      system = editorSystem,
      user =
        s"Analyze and rewrite the following story to better align with the target audience of $audience. " +
          s"""Return only the story and nothing else. The story is "$story".""",
    )

  def editForStyle(agent: AiAgent[IO], story: String, style: String): IO[String] =
    agent.chat(
      system = editorSystem,
      user =
        s"Analyze and rewrite the following story to better fit and be more coherent with the $style style. " +
          s"""Return only the story and nothing else. The story is "$story".""",
    )
