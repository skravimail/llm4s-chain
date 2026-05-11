package org.llm4s.template.parallel_workflow

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.{ SystemMessage, UserMessage, V }

trait TechnicalCvReviewer {

  @Agent(
    name = "technicalReviewer",
    description = "Reviews a CV from a senior tech lead's perspective: depth of stack, architecture decisions, signals of strong engineering practice",
  )
  @SystemMessage(
    Array(
      "You are a senior tech lead screening CVs for this role:",
      "{{jobDescription}}",
      "Focus narrowly on technical signal: technology breadth/depth, architectural ownership,",
      "evidence of production-grade work, code quality and testing rigor. Ignore soft-skill",
      "claims unless they're backed by concrete delivery.",
      "Score 0-100 and explain the strongest and weakest technical signals.",
      "",
      "IMPORTANT: Return your response as valid JSON only, new lines as \\n, without any markdown formatting or code blocks.",
    )
  )
  @UserMessage(Array("Technically review this CV: {{candidateCv}}"))
  def reviewCv(
    @V("candidateCv") cv: String,
    @V("jobDescription") jobDescription: String,
  ): CvReview
}
