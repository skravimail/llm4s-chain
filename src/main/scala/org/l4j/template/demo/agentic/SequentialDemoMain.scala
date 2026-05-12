package org.l4j.template.demo.agentic

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.demo.BackendSupport
import org.l4j.template.llm4s.agentic.Agent
import org.l4j.template.llm4s.agentic.AgentScope
import org.l4j.template.llm4s.structured.AiAgent

object SequentialDemoMain extends IOApp.Simple:

  override def run: IO[Unit] =
    BackendSupport.fromEnv.use { backend =>
      val agent = AiAgent[IO](backend)

      val writerAgent = Agent.liftScoped[IO, StoryDraft, StoryDraft]("writer") { (draft, _) =>
        SequentialDemoApi.generateStory(agent, draft.topic).map(story => draft.copy(story = story))
      }
      val audienceAgent = Agent.liftScoped[IO, StoryDraft, StoryDraft]("audienceEditor") { (draft, _) =>
        SequentialDemoApi.editForAudience(agent, draft.story, draft.audience).map(story => draft.copy(story = story))
      }
      val styleAgent = Agent.liftScoped[IO, StoryDraft, StoryDraft]("styleEditor") { (draft, _) =>
        SequentialDemoApi.editForStyle(agent, draft.story, draft.style).map(story => draft.copy(story = story))
      }

      val workflow = writerAgent.workflow
        .andThen(audienceAgent.workflow)
        .andThen(styleAgent.workflow)

      val input = StoryDraft(
        topic = "dragons and wizards",
        audience = "young adults",
        style = "fantasy",
        story = "",
      )

      for
        scope   <- AgentScope.create[IO]
        started <- IO(System.nanoTime())
        result  <- workflow.run(input, scope)
        elapsed <- IO((System.nanoTime() - started) / 1_000_000)
        _       <- IO.println("\n== Sequential pipeline (AiAgent + native Workflow) ==")
        _       <- IO.println(s"input: $input")
        _       <- IO.println("\n--- Final story ---")
        _       <- IO.println(result.story)
        _       <- IO.println(s"\n(pipeline completed in $elapsed ms)")
      yield ()
    }
