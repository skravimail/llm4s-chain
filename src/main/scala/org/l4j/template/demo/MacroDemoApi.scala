package org.l4j.template.demo

import org.l4j.template.llm4s.macros.system
import org.l4j.template.llm4s.macros.user
import upickle.default.ReadWriter

trait Assistant:
  @system("You are a helpful programming tutor. Answer concisely.")
  @user("Question: {{q}}")
  def ask(q: String): String

final case class CvReview(score: Int, feedback: String) derives ReadWriter

trait Reviewer:
  @system(
    "You are a hiring manager. Score the CV (0-100) against the job description and give terse feedback. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown, no prose."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  def review(candidateCv: String, jobDescription: String): CvReview

trait Tutor:
  @system(
    "You are a programming tutor. When the user asks about a term, call the `define` tool first to get " +
      "a precise definition, then expand on it in one or two sentences."
  )
  @user("Explain: {{topic}}")
  def explain(topic: String): String
