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
      config: RuntimeConfig = RuntimeConfig(),
  ): F[List[ChatMessage.ToolResultMessage]] =
    toolCalls.parTraverse { toolCall =>
      val context = InvocationContext(turn = turn, request = request, toolCall = toolCall)

      val effect = toolKit.executors.get(toolCall.name) match
        case Some(executor) =>
          runWithPolicy(executor, toolCall, context, config.toolFailurePolicy)
        case None =>
          config.unknownToolPolicy match
            case ToolErrorPolicy.FailFast =>
              MonadThrow[F].raiseError(AiRuntimeError.ToolMissing(toolCall.name))
            case ToolErrorPolicy.SurfaceToModel | ToolErrorPolicy.RetryOnce =>
              MonadThrow[F].pure(unknownToolResult(toolCall.name))

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
  ): F[ToolResult] =
    policy match
      case ToolErrorPolicy.SurfaceToModel =>
        executor.execute(toolCall, context).handleErrorWith(errorResultF)
      case ToolErrorPolicy.FailFast =>
        executor.execute(toolCall, context).adaptError { case t =>
          AiRuntimeError.ToolFailed(toolCall.name, t)
        }
      case ToolErrorPolicy.RetryOnce =>
        executor
          .execute(toolCall, context)
          .handleErrorWith { _ =>
            executor.execute(toolCall, context).handleErrorWith(errorResultF)
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
