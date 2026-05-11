package org.llm4s.template.l4j_macro.demo.agentic

import dev.langchain4j.agentic.Agent
import dev.langchain4j.service.V
import org.llm4s.template.l4j_macro.{ system, user }
import upickle.default.ReadWriter

/*
 * Two reviewer agents that fan out over the same (candidateCv, jobDescription)
 * input and write to distinct scope keys (`managerReview`, `technicalReview`),
 * so the parallel orchestrator can run them concurrently without contention.
 *
 * The case class return type travels through `@Agent`'s `outputKey` slot in the
 * AgenticScope; uPickle decodes the JSON the model emits.
 */

final case class CvScoredReview(score: Int, feedback: String) derives ReadWriter

trait ManagerReviewer:
  @system(
    "You are the hiring manager for this role. Score the CV 0-100 against the job description and " +
      "give terse, manager-flavoured feedback (leadership signals, team fit, hiring risk). " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  @Agent(outputKey = "managerReview", description = "Reviews a CV from a hiring manager's perspective")
  def reviewCv(@V("candidateCv") candidateCv: String, @V("jobDescription") jobDescription: String): CvScoredReview

trait TechnicalReviewer:
  @system(
    "You are a senior tech lead screening CVs. Focus narrowly on technical signal: stack depth, " +
      "architectural ownership, evidence of production-grade work, testing rigor. Score 0-100. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  @Agent(outputKey = "technicalReview", description = "Reviews a CV from a senior tech lead's perspective")
  def reviewCv(@V("candidateCv") candidateCv: String, @V("jobDescription") jobDescription: String): CvScoredReview
