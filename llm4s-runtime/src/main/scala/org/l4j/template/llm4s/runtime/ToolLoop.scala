package org.l4j.template.llm4s.runtime

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.TraceContext

object ToolLoop:

  /** Backwards-compatible entry point used by callers that don't carry a
    * `TraceContext`. Generates a fresh root context and a noop listener. */
  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
  ): F[List[ChatMessage.ToolResultMessage]] =
    executeAll(
      toolCalls,
      toolKit,
      turn,
      request,
      RuntimeConfig(),
      RuntimeListener.noop[F],
      TraceContext.fresh(),
    )

  /** Pre-PR-8b shape, kept for callers that already pass a listener and
    * config but not a trace. Mints a fresh root trace. */
  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
      config: RuntimeConfig,
      listener: RuntimeListener[F],
  ): F[List[ChatMessage.ToolResultMessage]] =
    executeAll(toolCalls, toolKit, turn, request, config, listener, TraceContext.fresh())

  /** Run all tool calls in the turn concurrently and collect their results
    * in the original call order. Each tool call gets its own child span
    * beneath `trace` so listeners can nest them. */
  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
      config: RuntimeConfig,
      listener: RuntimeListener[F],
      trace: TraceContext,
  ): F[List[ChatMessage.ToolResultMessage]] =
    toolCalls.parTraverse { toolCall =>
      val context = InvocationContext(
        turn = turn,
        request = request,
        toolCall = toolCall,
        trace = trace.child(),
      )

      val effect = toolKit.executors.get(toolCall.name) match
        case Some(executor) =>
          listener.spanToolCall(toolCall, context) {
            listener.onToolCalled(toolCall, context) >>
              runWithPolicy(executor, toolCall, context, config.toolFailurePolicy, listener)
          }
        case None =>
          val missing = AiRuntimeError.ToolMissing(toolCall.name)
          listener.onToolFailed(toolCall, context, missing, attempt = 1, willRetry = false) >>
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
    def attempt(attemptNo: Int): F[ToolResult] =
      val started = MonadThrow[F].pure(System.nanoTime())
      started.flatMap { start =>
        executor.execute(toolCall, context).flatTap { result =>
          listener.onToolSucceeded(toolCall, context, result, System.nanoTime() - start)
        }
      }

    policy match
      case ToolErrorPolicy.SurfaceToModel =>
        attempt(1).handleErrorWith { t =>
          listener.onToolFailed(toolCall, context, t, attempt = 1, willRetry = false) >>
            errorResultF(t)
        }
      case ToolErrorPolicy.FailFast =>
        attempt(1).handleErrorWith { t =>
          listener.onToolFailed(toolCall, context, t, attempt = 1, willRetry = false) >>
            MonadThrow[F].raiseError(AiRuntimeError.ToolFailed(toolCall.name, t))
        }
      case ToolErrorPolicy.RetryOnce =>
        attempt(1).handleErrorWith { first =>
          listener.onToolFailed(toolCall, context, first, attempt = 1, willRetry = true) >>
            attempt(2).handleErrorWith { second =>
              listener.onToolFailed(toolCall, context, second, attempt = 2, willRetry = false) >>
                errorResultF(second)
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
