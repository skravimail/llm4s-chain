package org.l4j.template.llm4s.core

/** Provider-neutral correctness checks every `ChatBackend[F]` is expected to
  * obey.
  *
  * The intent is *not* to test the network — pass in a backend wired against
  * a stub transport. The asserted properties live above the wire:
  *
  *   - message ordering is preserved across `chat` invocations;
  *   - tool schemas registered on the request are not silently dropped or
  *     reordered when the backend round-trips them;
  *   - finish reasons returned to callers come from `FinishReason`'s closed
  *     set (no arbitrary strings invented mid-pipeline);
  *   - a response's `text` matches its message contents.
  *
  * Backends include these by calling `ChatBackendLaws.checkAll(backend, ...)`
  * from a munit `FunSuite`.
  */
object ChatBackendLaws:

  final case class LawFailure(name: String, detail: String):
    override def toString: String = s"[$name] $detail"

  /** Verify that a chat response itself is internally consistent. Does NOT
    * invoke the backend — for that, pair this with [[checkMessageOrderPreserved]]
    * and friends.
    */
  def checkResponseInvariants(response: ChatResponse): List[LawFailure] =
    val errs = collection.mutable.ListBuffer.empty[LawFailure]

    // response.text mirrors message text
    if response.text != response.message.text then
      errs += LawFailure(
        "response_text_matches_message",
        s"response.text=${response.text.take(40)} but message.text=${response.message.text.take(40)}",
      )

    // finish reason — if present, must be a member of the closed set
    response.finishReason.foreach { fr =>
      if !FinishReason.values.contains(fr) then
        errs += LawFailure(
          "finish_reason_in_closed_set",
          s"unexpected finish reason: $fr",
        )
    }

    errs.toList

  /** Verify that calling the backend with a sequence of user messages
    * preserves their order in the request the transport sees. Caller supplies
    * a function that returns the captured request body after invoking `chat`. */
  def checkMessageOrderPreserved(
      request: ChatRequest,
      observedMessageRoles: List[String],
  ): List[LawFailure] =
    val expected = request.messages.map(_.role)
    if expected != observedMessageRoles then
      List(
        LawFailure(
          "message_ordering_preserved",
          s"expected $expected, transport saw $observedMessageRoles",
        )
      )
    else Nil

  /** Verify the tool schemas registered in the request are reflected in
    * whatever the transport observed. */
  def checkToolsRoundTrip(
      request: ChatRequest,
      observedToolNames: List[String],
  ): List[LawFailure] =
    val expected = request.tools.map(_.name)
    if expected.toSet != observedToolNames.toSet then
      List(
        LawFailure(
          "tools_round_trip",
          s"expected ${expected.sorted}, transport saw ${observedToolNames.sorted}",
        )
      )
    else Nil

  /** Aggregate all failures into one assertion string suitable for munit. */
  def report(failures: List[LawFailure]): Option[String] =
    if failures.isEmpty then None
    else Some(failures.mkString("ChatBackend law violations:\n  - ", "\n  - ", ""))
