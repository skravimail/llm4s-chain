package org.l4j.template.l4j_macro.demo.agentic

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.V
import org.l4j.template.l4j_macro.{ system, user }

/*
 * Traits that wear two hats:
 *
 *   - `@system` / `@user` are read by `AiService.materialize` at compile time:
 *     {{var}} placeholders are validated against parameter names, prompt
 *     building is emitted directly into the impl's method body.
 *
 *   - `@Agent` / `@V` are read at runtime by `AgenticServices` when it walks
 *     the trait via the AgenticBridge proxy. `outputKey` tells the orchestrator
 *     which scope slot to write to; `@V("...")` (or the parameter name, by
 *     fallback) tells it which slot to read.
 *
 * The two annotation sets are complementary — neither does the other's job.
 */

trait CreativeWriter:
  @system("You are a creative writer.")
  @user(
    "Generate a draft of a story no more than 3 sentences long around the given topic. " +
      "Return only the story and nothing else. The topic is {{topic}}."
  )
  @Agent(outputKey = "story", description = "Generates a story based on the given topic")
  def generateStory(@V("topic") topic: String): String

trait AudienceEditor:
  @system("You are a professional editor.")
  @user(
    "Analyze and rewrite the following story to better align with the target audience of {{audience}}. " +
      "Return only the story and nothing else. The story is \"{{story}}\"."
  )
  @Agent(outputKey = "story", description = "Edits a story to better fit a given audience")
  def editStory(@V("story") story: String, @V("audience") audience: String): String

trait StyleEditor:
  @system("You are a professional editor.")
  @user(
    "Analyze and rewrite the following story to better fit and be more coherent with the {{style}} style. " +
      "Return only the story and nothing else. The story is \"{{story}}\"."
  )
  @Agent(outputKey = "story", description = "Edits a story to better fit a given style")
  def editStory(@V("story") story: String, @V("style") style: String): String
