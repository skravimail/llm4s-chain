package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.runtime.InvocationContext

final case class GuardrailChain[F[_]](
    input: List[InputGuardrail[F]] = Nil,
    output: List[OutputGuardrail[F]] = Nil,
    tools: List[ToolGuardrail[F]] = Nil,
):
  def checkInput(request: ChatRequest)(using F: MonadThrow[F]): F[ChatRequest] =
    input.foldLeft(request.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(value).flatMap(resolve))
    }

  def checkOutput(request: ChatRequest, response: ChatResponse)(using F: MonadThrow[F]): F[ChatResponse] =
    output.foldLeft(response.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(request, value).flatMap(resolve))
    }

  def checkTool(call: ToolCall, context: InvocationContext)(using F: MonadThrow[F]): F[ToolCall] =
    tools.foldLeft(call.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(value, context).flatMap(resolve))
    }

  private def resolve[A](result: GuardrailResult[A])(using F: MonadThrow[F]): F[A] =
    result match
      case GuardrailResult.Allow(value) => value.pure[F]
      case GuardrailResult.Block(v)     => F.raiseError(GuardrailBlockedException(v))

object GuardrailChain:
  def empty[F[_]]: GuardrailChain[F] =
    GuardrailChain[F]()

