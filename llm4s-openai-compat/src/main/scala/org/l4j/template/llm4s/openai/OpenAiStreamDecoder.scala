package org.l4j.template.llm4s.openai

import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.streaming.StreamEvent

object OpenAiStreamDecoder:

  def decodeLine(line: String): List[StreamEvent] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed == "data: [DONE]" then
      Nil
    else if trimmed.startsWith("data: ") then
      decodeJson(ujson.read(trimmed.stripPrefix("data: ")))
    else
      Nil

  private def decodeJson(json: ujson.Value): List[StreamEvent] =
    val choice = json("choices")(0)
    val delta = choice("delta")
    val finishReason = choice.obj.get("finish_reason").flatMap {
      case ujson.Str("stop")           => Some(FinishReason.Stop)
      case ujson.Str("length")         => Some(FinishReason.Length)
      case ujson.Str("tool_calls")     => Some(FinishReason.ToolCalls)
      case ujson.Str("content_filter") => Some(FinishReason.ContentFilter)
      case _                           => None
    }

    val deltas =
      delta.obj.get("content").collect { case ujson.Str(value) => StreamEvent.TextDelta(value) }.toList :::
        delta.obj.get("reasoning").collect { case ujson.Str(value) => StreamEvent.ThinkingDelta(value) }.toList :::
        delta.obj.get("tool_calls").toList.flatMap {
          case ujson.Arr(values) =>
            values.toList.flatMap { entry =>
              val function = entry.obj.get("function")
              val name = function.flatMap(_.obj.get("name")).collect { case ujson.Str(value) => value }
              val args = function.flatMap(_.obj.get("arguments")).collect { case ujson.Str(value) => value }.getOrElse("")
              val id = entry.obj.get("id").collect { case ujson.Str(value) => value }
              List(StreamEvent.ToolCallDelta(id, name, args))
            }
          case _ => Nil
        }

    val completed =
      finishReason.toList.map { reason =>
        StreamEvent.Completed(
          ChatResponse(
            message = ChatMessage.AiMessage(
              contents = Nil,
              toolCalls = Nil,
              finishReason = Some(reason),
            ),
            finishReason = Some(reason),
          )
        )
      }

    deltas ++ completed
