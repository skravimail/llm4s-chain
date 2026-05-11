package org.l4j.template.l4j_macro

import dev.langchain4j.data.message.{
  ChatMessage,
  SystemMessage as JSystemMessage,
  ToolExecutionResultMessage,
  UserMessage as JUserMessage,
}
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest

import scala.jdk.CollectionConverters.*

/**
 * Runtime support called from macro-generated AiService implementations.
 *
 * Keeps the macro itself small: the macro only emits the system/user strings, the
 * return-type decoder, and a call into one of these methods.
 */
object Runtime:

  /** Hard cap on tool-call ↔ model turns before we give up. */
  private val MaxTurns = 8

  /**
   * Send a system+user prompt to the model, optionally exposing tools.
   *
   * If `kit.specs` is empty we omit the toolSpecifications field entirely — some
   * models / endpoints choke on empty arrays.
   *
   * When the model emits tool calls we dispatch through `kit.dispatch`, append the
   * results, and re-prompt up to `MaxTurns` times. Returns the first AI message
   * that has no further tool requests.
   */
  def chat(
      model: ChatModel,
      system: Option[String],
      userText: String,
      kit: ToolKit,
  ): String =
    val msgs = scala.collection.mutable.ArrayBuffer.empty[ChatMessage]
    system.foreach(s => msgs += JSystemMessage.from(s))
    msgs += JUserMessage.from(userText)

    var turn = 0
    while turn < MaxTurns do
      turn += 1
      val builder = ChatRequest.builder().messages(msgs.toList.asJava)
      if kit.specs.nonEmpty then builder.toolSpecifications(kit.specs.asJava)
      val response = model.chat(builder.build())
      val ai       = response.aiMessage()
      msgs += ai

      val toolRequests = Option(ai.toolExecutionRequests())
        .map(_.asScala.toList)
        .getOrElse(Nil)

      if toolRequests.isEmpty then return Option(ai.text()).getOrElse("")

      toolRequests.foreach { req =>
        val result = kit.dispatch.get(req.name()) match
          case Some(fn) =>
            try fn(req.arguments())
            catch case t: Throwable => s"""{"error":"${escape(t.getMessage)}"}"""
          case None =>
            s"""{"error":"no such tool: ${escape(req.name())}"}"""
        msgs += ToolExecutionResultMessage.from(req, result)
      }
    end while
    sys.error(s"AiService chat exceeded $MaxTurns tool-call turns")

  private def escape(s: String): String =
    Option(s).getOrElse("").replace("\\", "\\\\").replace("\"", "\\\"")
