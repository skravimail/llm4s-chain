package org.l4j.template.llm4s.guardrails

import cats.Functor
import cats.syntax.functor.*
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse

final case class ModerationResult(
    flagged: Boolean,
    categories: Set[String] = Set.empty,
    scores: Map[String, Double] = Map.empty,
    metadata: Map[String, String] = Map.empty,
)

trait ModerationModel[F[_]]:
  def moderate(text: String): F[ModerationResult]

object ModerationGuardrails:
  def input[F[_]: Functor](
      model: ModerationModel[F],
      violationCode: String = "moderation.input_blocked",
  ): InputGuardrail[F] =
    new InputGuardrail[F]:
      override def check(request: ChatRequest): F[GuardrailResult[ChatRequest]] =
        model.moderate(request.messages.map(_.text).mkString("\n")).map { result =>
          if result.flagged then GuardrailResult.Block(toViolation(violationCode, result))
          else GuardrailResult.Allow(request)
        }

  def output[F[_]: Functor](
      model: ModerationModel[F],
      violationCode: String = "moderation.output_blocked",
  ): OutputGuardrail[F] =
    new OutputGuardrail[F]:
      override def check(request: ChatRequest, response: ChatResponse): F[GuardrailResult[ChatResponse]] =
        model.moderate(response.text).map { result =>
          if result.flagged then GuardrailResult.Block(toViolation(violationCode, result))
          else GuardrailResult.Allow(response)
        }

  private def toViolation(code: String, result: ModerationResult): GuardrailViolation =
    GuardrailViolation(
      code = code,
      message = s"Moderation flagged categories: ${result.categories.toList.sorted.mkString(",")}",
      metadata = result.metadata ++ result.scores.map { case (name, score) => s"score.$name" -> score.toString },
    )

