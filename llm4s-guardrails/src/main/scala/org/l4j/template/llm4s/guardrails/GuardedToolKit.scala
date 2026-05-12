package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.ToolExecutor
import org.l4j.template.llm4s.runtime.ToolKit

object GuardedToolKit:
  def apply[F[_]: MonadThrow](
      underlying: ToolKit[F],
      guardrails: GuardrailChain[F],
  ): ToolKit[F] =
    val executors = underlying.executors.view.mapValues { executor =>
      GuardedToolExecutor(executor, guardrails)
    }.toMap
    ToolKit(underlying.schemas, executors)

private final case class GuardedToolExecutor[F[_]: MonadThrow](
    underlying: ToolExecutor[F],
    guardrails: GuardrailChain[F],
) extends ToolExecutor[F]:

  override def execute(call: ToolCall, context: InvocationContext): F[ToolResult] =
    guardrails
      .checkTool(call, context)
      .flatMap(underlying.execute(_, context))
      .recover { case GuardrailBlockedException(violation) =>
        ToolResult.StructuredJson(
          s"""{"error":"tool call blocked by guardrail","code":"${escape(violation.code)}","message":"${escape(violation.message)}"}""",
          isError = true,
        )
      }

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

