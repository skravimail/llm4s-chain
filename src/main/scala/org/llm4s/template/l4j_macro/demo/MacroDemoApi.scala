package org.llm4s.template.l4j_macro.demo

import org.llm4s.template.l4j_macro.{ param, system, tool, user }
import upickle.default.ReadWriter

// ──────────────────────────────────────────────────────────────────────────────
// 1. Plain chat: templated user prompt, String return.
// ──────────────────────────────────────────────────────────────────────────────
trait Assistant:
  @system("You are a helpful programming tutor. Answer concisely.")
  @user("Question: {{q}}")
  def ask(q: String): String

// ──────────────────────────────────────────────────────────────────────────────
// 2. Typed return: case class with derived uPickle ReadWriter — no Java POJO.
// ──────────────────────────────────────────────────────────────────────────────
final case class CvReview(score: Int, feedback: String) derives ReadWriter

trait Reviewer:
  @system(
    "You are a hiring manager. Score the CV (0-100) against the job description and give terse feedback. " +
      "Return ONLY a JSON object with keys `score` (integer) and `feedback` (string). No markdown, no prose."
  )
  @user("Job description:\n{{jobDescription}}\n\nCandidate CV:\n{{candidateCv}}")
  def review(candidateCv: String, jobDescription: String): CvReview

// ──────────────────────────────────────────────────────────────────────────────
// 3. Tool-using assistant.
// ──────────────────────────────────────────────────────────────────────────────
final class WikiLookup:
  @tool("Look up a one-line definition for a programming term")
  def define(@param("Term to look up") term: String): String =
    term.toLowerCase match
      case "monad"   => "A monad is a design pattern for sequencing effectful computations."
      case "functor" => "A functor maps elements of one set to another while preserving structure."
      case "fiber"   => "A fiber is a lightweight, cooperatively-scheduled unit of concurrent execution."
      case _         => s"No definition found for '$term'"

trait Tutor:
  @system(
    "You are a programming tutor. When the user asks about a term, call the `define` tool first to get " +
      "a precise definition, then expand on it in one or two sentences."
  )
  @user("Explain: {{topic}}")
  def explain(topic: String): String
