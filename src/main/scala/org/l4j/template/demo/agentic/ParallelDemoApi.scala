package org.l4j.template.demo.agentic

import cats.effect.IO
import org.l4j.template.llm4s.structured.AiAgent
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

final case class CvScoredReview(score: Int, feedback: String)
    derives StructuredCodec,
      SchemaEncoder,
      ValueDecoder

final case class CvUnderReview(candidateCv: String, jobDescription: String)

object ParallelDemoApi:

  private val managerSystem =
    "You are the hiring manager for this role. Score the CV 0-100 against the job description and " +
      "give terse, manager-flavoured feedback (leadership signals, team fit, hiring risk). " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."

  private val technicalSystem =
    "You are a senior tech lead screening CVs. Focus narrowly on technical signal: stack depth, " +
      "architectural ownership, evidence of production-grade work, testing rigor. Score 0-100. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown."

  def reviewByManager(agent: AiAgent[IO], cv: String, jd: String): IO[CvScoredReview] =
    agent.chatAs[CvScoredReview](
      system = managerSystem,
      user = s"Job description:\n$jd\n\nCandidate CV:\n$cv",
    )

  def reviewByTechnical(agent: AiAgent[IO], cv: String, jd: String): IO[CvScoredReview] =
    agent.chatAs[CvScoredReview](
      system = technicalSystem,
      user = s"Job description:\n$jd\n\nCandidate CV:\n$cv",
    )
