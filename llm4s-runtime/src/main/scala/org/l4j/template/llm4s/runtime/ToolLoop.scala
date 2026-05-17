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
    * the original call order. The OpenAI tool spec allows the model to emit
    * multiple parallel tool calls in a single turn; doing them sequentially
    * needlessly serialises tool latency. Result ordering is preserved by
    * `parTraverse`. */
  def executeAll[F[_]: MonadThrow: Parallel](
      toolCalls: List[ToolCall],
      toolKit: ToolKit[F],
      turn: Int,
      request: ChatRequest,
  ): F[List[ChatMessage.ToolResultMessage]] =
    toolCalls.parTraverse { toolCall =>
      val context = InvocationContext(turn = turn, request = request, toolCall = toolCall)

      val effect = toolKit.executors.get(toolCall.name) match
        case Some(executor) =>
          executor.execute(toolCall, context).handleErrorWith(errorResult)
        case None =>
          MonadThrow[F].pure(
            ToolResult.StructuredJson(
              ujson.write(ujson.Obj("error" -> s"no such tool: ${toolCall.name}")),
              isError = true,
            )
          )

      effect.map(result =>
        ChatMessage.ToolResultMessage(
          toolName = toolCall.name,
          toolCallId = toolCall.callId,
          result = result,
        )
      )
    }

  private def errorResult[F[_]: MonadThrow](t: Throwable): F[ToolResult] =
    MonadThrow[F].pure(
      ToolResult.StructuredJson(
        ujson.write(ujson.Obj("error" -> Option(t.getMessage).getOrElse(t.getClass.getSimpleName))),
        isError = true,
      )
    )
