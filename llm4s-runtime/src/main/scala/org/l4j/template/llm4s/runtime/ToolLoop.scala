package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult

object ToolLoop:

  /** Run all tool calls in the turn concurrently and collect their results in
    * the original call order. Per-call behaviour on failure or unknown name
    * is controlled by [[RuntimeConfig.toolFailurePolicy]] /
    * [[RuntimeConfig.unknownToolPolicy]].
    */
  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
  ): F[List[ChatMessage.ToolResultMessage]] =
    executeAll(toolCalls, toolKit, turn, request, RuntimeConfig(), RuntimeListener.noop[F])

  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
      config: RuntimeConfig,
      listener: RuntimeListener[F],
  ): F[List[ChatMessage.ToolResultMessage]] =
    toolCalls.parTraverse { toolCall =>
      val context = InvocationContext(turn = turn, request = request, toolCall = toolCall)

      val effect = toolKit.executors.get(toolCall.name) match
        case Some(executor) =>
          listener.onToolCalled(toolCall, context) >>
            runWithPolicy(executor, toolCall, context, config.toolFailurePolicy, listener)
        case None =>
          val missing = AiRuntimeError.ToolMissing(toolCall.name)
          listener.onToolFailed(toolCall, context, missing) >>
            (config.unknownToolPolicy match
              case ToolErrorPolicy.FailFast =>
                MonadThrow[F].raiseError(missing)
              case ToolErrorPolicy.SurfaceToModel | ToolErrorPolicy.RetryOnce =>
                MonadThrow[F].pure(unknownToolResult(toolCall.name)))

      effect.map(result =>
        ChatMessage.ToolResultMessage(
          toolName = toolCall.name,
          toolCallId = toolCall.callId,
          result = result,
        )
      )
    }

  private def runWithPolicy[F[_]: MonadThrow](
      executor: ToolExecutor[F],
      toolCall: ToolCall,
      context: InvocationContext,
      policy: ToolErrorPolicy,
      listener: RuntimeListener[F],
  ): F[ToolResult] =
    val attempt = executor.execute(toolCall, context).flatTap { result =>
      listener.onToolSucceeded(toolCall, context, result)
    }
    policy match
      case ToolErrorPolicy.SurfaceToModel =>
        attempt.handleErrorWith { t =>
          listener.onToolFailed(toolCall, context, t) >> errorResultF(t)
        }
      case ToolErrorPolicy.FailFast =>
        attempt.handleErrorWith { t =>
          listener.onToolFailed(toolCall, context, t) >>
            MonadThrow[F].raiseError(AiRuntimeError.ToolFailed(toolCall.name, t))
        }
      case ToolErrorPolicy.RetryOnce =>
        attempt.handleErrorWith { first =>
          listener.onToolFailed(toolCall, context, first) >>
            attempt.handleErrorWith { second =>
              listener.onToolFailed(toolCall, context, second) >> errorResultF(second)
            }
        }

  private def errorResultF[F[_]: MonadThrow](t: Throwable): F[ToolResult] =
    MonadThrow[F].pure(
      ToolResult.StructuredJson(
        ujson.write(
          ujson.Obj("error" -> Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
        ),
        isError = true,
      )
    )

  private def unknownToolResult(name: String): ToolResult =
    ToolResult.StructuredJson(
      ujson.write(ujson.Obj("error" -> s"no such tool: $name")),
      isError = true,
    )
