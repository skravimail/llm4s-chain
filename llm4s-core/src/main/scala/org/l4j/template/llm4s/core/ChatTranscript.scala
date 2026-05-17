package org.l4j.template.llm4s.core

/** A structured view of a chat conversation that separates the system prompt
  * from the actual back-and-forth.
  *
  * The runtime carries messages as a flat `List[ChatMessage]` because that is
  * what the wire wants. But at the *persistence* boundary we usually want to
  * drop the system prompt (it's owned by the caller, not the conversation)
  * and only store the user/assistant/tool turns. The previous implementation
  * assumed there was at most one system message and that it was always first
  * — both assumptions break in real flows. This type lets us do the split
  * correctly regardless of position or count.
  */
final case class ChatTranscript(
    system: Option[ChatMessage.SystemMessage],
    turns: List[ChatMessage],
):
  /** Flatten back to the wire-shaped list. The (possibly merged) system
    * message always appears first. */
  def toMessages: List[ChatMessage] =
    system.toList ++ turns

  /** Append a new turn (user/assistant/tool); rejects system messages so the
    * system slot is only mutated via [[withSystem]]. */
  def withTurn(message: ChatMessage): ChatTranscript =
    message match
      case _: ChatMessage.SystemMessage =>
        throw new IllegalArgumentException("Use withSystem to set the system message")
      case other => copy(turns = turns :+ other)

  def withSystem(text: String): ChatTranscript =
    copy(system = Some(ChatMessage.SystemMessage.from(text)))

  def withoutSystem: ChatTranscript = copy(system = None)

object ChatTranscript:
  def empty: ChatTranscript = ChatTranscript(None, Nil)

  /** Split a flat list into a system prompt + turns.
    *
    * - Zero system messages → `system = None`.
    * - One system message → that message goes in `system`, regardless of its
    *   position in the input.
    * - Multiple system messages → their textual contents are concatenated
    *   (separated by a single newline) into a single system message. This
    *   matches how every provider treats the leading system prompt today;
    *   if you genuinely need to send multiple, build the request manually.
    *
    * Non-system message order is preserved.
    */
  def fromMessages(messages: List[ChatMessage]): ChatTranscript =
    val systems = messages.collect { case s: ChatMessage.SystemMessage => s }
    val turns = messages.filterNot(_.isInstanceOf[ChatMessage.SystemMessage])
    val mergedSystem = systems match
      case Nil      => None
      case one :: Nil => Some(one)
      case many =>
        val texts = many.flatMap(_.contents.flatMap(_.textValue)).mkString("\n")
        Some(ChatMessage.SystemMessage.from(texts))
    ChatTranscript(mergedSystem, turns)
