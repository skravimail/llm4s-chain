package org.llm4s.template.parallel_workflow

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.{ SystemMessage, UserMessage, V }

trait ManagerCvReviewer {

  @Agent(
    name = "managerReviewer",
    description = "Reviews a CV based on a job description, gives feedback and a score",
  )
  @SystemMessage(
    Array(
      "You are the hiring manager for this job:",
      "{{jobDescription}}",
      "You review applicant CVs and need to decide who of the many applicants you invite for an on-site interview.",
      "You give each CV a score (0-100) and feedback (both the good and the bad things).",
      "You can ignore things like missing address and placeholders.",
      "",
      "IMPORTANT: Return your response as valid JSON only, new lines as \\n, without any markdown formatting or code blocks.",
    )
  )
  @UserMessage(Array("Review this CV: {{candidateCv}}"))
  def reviewCv(
    @V("candidateCv") cv: String,
    @V("jobDescription") jobDescription: String,
  ): CvReview
}
