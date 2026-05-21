package org.l4j.template.llm4s.openai

import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.streaming.StreamEvent

object OpenAiStreamDecoder:

  def decodeLine(line: String): List[StreamEvent] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith(":") || trimmed == "data: [DONE]" then
      // SSE keepalive lines start with `:`; empty / [DONE] are framing.
      Nil
    else if trimmed.startsWith("data: ") then
      // Providers occasionally emit partial chunks during reconnects or
      // back-pressure. Don't propagate parser errors out of the decoder —
      // skip the bad line so the rest of the stream keeps flowing.
      scala.util.Try(ujson.read(trimmed.stripPrefix("data: "))).toOption
        .toList
        .flatMap(decodeJson)
    else
      Nil

  private def decodeJson(json: ujson.Value): List[StreamEvent] =
    val model = json.obj.get("model").collect { case ujson.Str(value) => value }
    val choices = json.obj.get("choices").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    choices.headOption.toList.flatMap(choice => decodeChoice(choice, model))

  private def decodeChoice(choice: ujson.Value, model: Option[String]): List[StreamEvent] =
    val delta = choice.obj.get("delta").flatMap(_.objOpt)
    val finishReason = choice.obj.get("finish_reason").flatMap {
      case ujson.Str("stop")           => Some(FinishReason.Stop)
      case ujson.Str("length")         => Some(FinishReason.Length)
      case ujson.Str("tool_calls")     => Some(FinishReason.ToolCalls)
      case ujson.Str("content_filter") => Some(FinishReason.ContentFilter)
      case _                           => None
    }

    val deltas = delta.toList.flatMap { fields =>
      val values = fields.value
      values.get("content").collect { case ujson.Str(value) => StreamEvent.TextDelta(value) }.toList :::
        values.get("reasoning").collect { case ujson.Str(value) => StreamEvent.ThinkingDelta(value) }.toList :::
        values.get("tool_calls").toList.flatMap {
          case ujson.Arr(entries) =>
            entries.toList.flatMap { entry =>
              val function = entry.obj.get("function").flatMap(_.objOpt)
              val name = function.flatMap(_.value.get("name")).collect { case ujson.Str(value) => value }
              val args = function.flatMap(_.value.get("arguments")).collect { case ujson.Str(value) => value }
              val id = entry.obj.get("id").collect { case ujson.Str(value) => value }
              if id.isEmpty && name.isEmpty && args.isEmpty then Nil
              else List(StreamEvent.ToolCallDelta(id, name, args.getOrElse("")))
            }
          case _ => Nil
        }
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
            model = model,
          )
        )
      }

    deltas ++ completed
