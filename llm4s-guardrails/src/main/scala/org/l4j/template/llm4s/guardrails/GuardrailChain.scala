package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.InvocationContext
import org.l4j.template.llm4s.core.ToolCall

/** A chain of input / output / tool guardrails.
  *
  * `listener` is left at `None` by default so the case class stays trivially
  * constructible from any F without needing an `Applicative[F]` at the
  * default-arg site. Adopters who want observability supply one via the
  * `withListener` builder.
  */
final case class GuardrailChain[F[_]](
    input: List[InputGuardrail[F]] = Nil,
    output: List[OutputGuardrail[F]] = Nil,
    tools: List[ToolGuardrail[F]] = Nil,
    listener: Option[GuardrailListener[F]] = None,
):

  def withListener(l: GuardrailListener[F]): GuardrailChain[F] =
    copy(listener = Some(l))

  def checkInput(
      request: ChatRequest
  )(using F: MonadThrow[F], P: Parallel[F]): F[ChatRequest] =
    parCheck(input)(_.check(request))(v => fireInput(request, v))
      .as(request)

  def checkOutput(
      request: ChatRequest,
      response: ChatResponse,
  )(using F: MonadThrow[F], P: Parallel[F]): F[ChatResponse] =
    parCheck(output)(_.check(request, response))(v => fireOutput(request, response, v))
      .as(response)

  def checkTool(
      call: ToolCall,
      context: InvocationContext,
  )(using F: MonadThrow[F], P: Parallel[F]): F[ToolCall] =
    parCheck(tools)(_.check(call, context))(v => fireTool(call, context, v))
      .as(call)

  def checkInputSequential(request: ChatRequest)(using F: MonadThrow[F]): F[ChatRequest] =
    input.foldLeft(request.pure[F]) { (current, guardrail) =>
      current.flatMap(value =>
        guardrail.check(value).flatMap(resolve(_, v => fireInput(request, v)))
      )
    }

  def checkOutputSequential(
      request: ChatRequest,
      response: ChatResponse,
  )(using F: MonadThrow[F]): F[ChatResponse] =
    output.foldLeft(response.pure[F]) { (current, guardrail) =>
      current.flatMap(value =>
        guardrail.check(request, value).flatMap(resolve(_, v => fireOutput(request, value, v)))
      )
    }

  def checkToolSequential(call: ToolCall, context: InvocationContext)(using
      F: MonadThrow[F]
  ): F[ToolCall] =
    tools.foldLeft(call.pure[F]) { (current, guardrail) =>
      current.flatMap(value =>
        guardrail.check(value, context).flatMap(resolve(_, v => fireTool(value, context, v)))
      )
    }

  private def fireInput(req: ChatRequest, v: GuardrailViolation)(using F: MonadThrow[F]): F[Unit] =
    listener.fold(F.unit)(_.onInputBlocked(req, v))

  private def fireOutput(
      req: ChatRequest,
      resp: ChatResponse,
      v: GuardrailViolation,
  )(using F: MonadThrow[F]): F[Unit] =
    listener.fold(F.unit)(_.onOutputBlocked(req, resp, v))

  private def fireTool(
      call: ToolCall,
      ctx: InvocationContext,
      v: GuardrailViolation,
  )(using F: MonadThrow[F]): F[Unit] =
    listener.fold(F.unit)(_.onToolBlocked(call, ctx, v))

  private def parCheck[G, A](
      guardrails: List[G]
  )(
      check: G => F[GuardrailResult[A]]
  )(
      onBlocked: GuardrailViolation => F[Unit]
  )(using F: MonadThrow[F], P: Parallel[F]): F[Unit] =
    guardrails.parTraverse(check).flatMap { results =>
      results.collectFirst { case GuardrailResult.Block(v) => v } match
        case Some(violation) =>
          onBlocked(violation) >> F.raiseError(GuardrailBlockedException(violation))
        case None => F.unit
    }

  private def resolve[A](
      result: GuardrailResult[A],
      onBlocked: GuardrailViolation => F[Unit],
  )(using F: MonadThrow[F]): F[A] =
    result match
      case GuardrailResult.Allow(value) => value.pure[F]
      case GuardrailResult.Block(v)     =>
        onBlocked(v) >> F.raiseError(GuardrailBlockedException(v))

object GuardrailChain:
  def empty[F[_]]: GuardrailChain[F] =
    GuardrailChain[F]()
