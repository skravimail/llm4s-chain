package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.Parallel
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

  /** Run all input guardrails in parallel against the original request and
    * raise the first violation if any block. Use the parallel variant
    * (`checkInput`) when guardrails are independent checks — the common
    * case, including async moderation calls. For chained-transform style
    * (where each guardrail's Allow value feeds the next), use
    * [[checkInputSequential]]. */
  def checkInput(
      request: ChatRequest
  )(using F: MonadThrow[F], P: Parallel[F]): F[ChatRequest] =
    parCheck(input)(_.check(request)).as(request)

  def checkOutput(
      request: ChatRequest,
      response: ChatResponse,
  )(using F: MonadThrow[F], P: Parallel[F]): F[ChatResponse] =
    parCheck(output)(_.check(request, response)).as(response)

  def checkTool(
      call: ToolCall,
      context: InvocationContext,
  )(using F: MonadThrow[F], P: Parallel[F]): F[ToolCall] =
    parCheck(tools)(_.check(call, context)).as(call)

  /** Sequential input check that threads each guardrail's Allow value into
    * the next. Slower than [[checkInput]] but lets one guardrail mutate the
    * request for downstream guardrails. */
  def checkInputSequential(request: ChatRequest)(using F: MonadThrow[F]): F[ChatRequest] =
    input.foldLeft(request.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(value).flatMap(resolve))
    }

  def checkOutputSequential(
      request: ChatRequest,
      response: ChatResponse,
  )(using F: MonadThrow[F]): F[ChatResponse] =
    output.foldLeft(response.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(request, value).flatMap(resolve))
    }

  def checkToolSequential(call: ToolCall, context: InvocationContext)(using
      F: MonadThrow[F]
  ): F[ToolCall] =
    tools.foldLeft(call.pure[F]) { (current, guardrail) =>
      current.flatMap(value => guardrail.check(value, context).flatMap(resolve))
    }

  private def parCheck[G, A](
      guardrails: List[G]
  )(check: G => F[GuardrailResult[A]])(using F: MonadThrow[F], P: Parallel[F]): F[Unit] =
    guardrails.parTraverse(check).flatMap { results =>
      results.collectFirst { case GuardrailResult.Block(v) => v } match
        case Some(violation) => F.raiseError(GuardrailBlockedException(violation))
        case None            => F.unit
    }

  private def resolve[A](result: GuardrailResult[A])(using F: MonadThrow[F]): F[A] =
    result match
      case GuardrailResult.Allow(value) => value.pure[F]
      case GuardrailResult.Block(v)     => F.raiseError(GuardrailBlockedException(v))

object GuardrailChain:
  def empty[F[_]]: GuardrailChain[F] =
    GuardrailChain[F]()

