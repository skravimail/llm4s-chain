package org.l4j.template.demo.agentic

import org.l4j.template.llm4s.macros.system
import org.l4j.template.llm4s.macros.user
import upickle.default.ReadWriter

final case class CvScoredReview(score: Int, feedback: String) derives ReadWriter

trait ManagerReviewer:
  @system(
    "You are the hiring manager for this role. Score the CV 0-100 against the job description and " +
      "give terse, manager-flavoured feedback (leadership signals, team fit, hiring risk). " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  def reviewCv(candidateCv: String, jobDescription: String): CvScoredReview

trait TechnicalReviewer:
  @system(
    "You are a senior tech lead screening CVs. Focus narrowly on technical signal: stack depth, " +
      "architectural ownership, evidence of production-grade work, testing rigor. Score 0-100. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  def reviewCv(candidateCv: String, jobDescription: String): CvScoredReview

final case class CvUnderReview(candidateCv: String, jobDescription: String)
