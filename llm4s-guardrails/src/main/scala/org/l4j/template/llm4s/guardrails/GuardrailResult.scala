package org.l4j.template.llm4s.guardrails

final case class GuardrailViolation(
    code: String,
    message: String,
    metadata: Map[String, String] = Map.empty,
)

sealed trait GuardrailResult[+A]

object GuardrailResult:
  final case class Allow[A](value: A) extends GuardrailResult[A]
  final case class Block(violation: GuardrailViolation) extends GuardrailResult[Nothing]

final case class GuardrailBlockedException(
    violation: GuardrailViolation
) extends RuntimeException(s"${violation.code}: ${violation.message}")

