package org.l4j.template.llm4s.structured

import scala.annotation.tailrec
import scala.util.Try

/** Pulls a parseable JSON value out of arbitrary model output.
  *
  * The OpenAI `response_format=json_schema` mode guarantees the entire
  * response is a single valid JSON document. The older `json_object` mode
  * only guarantees *valid JSON*. Servers that don't implement either (or
  * small local models like `gemma-4-e2b`) routinely wrap their JSON in
  * markdown fences, prefix it with prose ("Here is the JSON: ..."),
  * suffix it with explanations, or do all three. This extractor tries the
  * three common shapes in order and returns the first one that parses.
  *
  * Strategy:
  *   1. Try to parse the trimmed input as JSON directly.
  *   2. If the input is wrapped in a ```...``` or ```json ... ``` fence,
  *      strip the fence and retry.
  *   3. Find the first balanced `{...}` or `[...]` block (string-aware,
  *      so braces inside strings don't confuse the depth counter) and
  *      try to parse it.
  *
  * Returns `Right(value)` if any step succeeds; `Left(message)` with a
  * preview of the input otherwise.
  */
object JsonExtractor:

  def extract(raw: String): Either[String, ujson.Value] =
    val trimmed = raw.trim
    tryParse(trimmed)
      .orElse(stripFence(trimmed).flatMap(tryParse))
      .orElse(firstBalancedBlock(trimmed).flatMap(tryParse))
      .toRight(s"could not extract JSON from response (head: ${trimmed.take(80)}${if trimmed.length > 80 then "…" else ""})")

  private def tryParse(s: String): Option[ujson.Value] =
    Try(ujson.read(s.trim)).toOption

  /** If `text` is fenced (```...```), return the body. Recognizes an
    * optional language tag (`json`, `JSON`, `JSON5`, etc.) after the
    * opening fence. */
  private def stripFence(text: String): Option[String] =
    val fence = "```"
    if !text.startsWith(fence) then None
    else
      val afterOpen = text.stripPrefix(fence)
      // Drop an optional language tag up to the first newline.
      val withoutTag = afterOpen.indexOf('\n') match
        case -1  => afterOpen
        case nl  =>
          val firstLine = afterOpen.take(nl).trim
          // First line is a tag if it contains no whitespace and no JSON
          // structural chars.
          val looksLikeTag =
            firstLine.nonEmpty &&
              firstLine.length < 16 &&
              firstLine.forall(c => c.isLetterOrDigit) &&
              !firstLine.contains('{') &&
              !firstLine.contains('[')
          if looksLikeTag then afterOpen.drop(nl + 1) else afterOpen
      val end = withoutTag.lastIndexOf(fence)
      if end < 0 then None else Some(withoutTag.substring(0, end))

  /** Locate the first complete `{...}` or `[...]` block in `text`. Aware
    * of JSON string literals (so a `{` inside `"a {b} c"` doesn't open a
    * new object) and standard `\` escapes (so `"\""` doesn't end the
    * string). */
  private def firstBalancedBlock(text: String): Option[String] =
    val startIdx = text.indexWhere(c => c == '{' || c == '[')
    if startIdx < 0 then None
    else
      val opener = text(startIdx)
      val closer = if opener == '{' then '}' else ']'
      scan(text, startIdx, opener, closer)

  @tailrec
  private def scan(
      text: String,
      startIdx: Int,
      opener: Char,
      closer: Char,
      i: Int = -1,
      depth: Int = 0,
      inString: Boolean = false,
      escaped: Boolean = false,
  ): Option[String] =
    val idx = if i < 0 then startIdx else i
    if idx >= text.length then None
    else
      val c = text(idx)
      if escaped then
        scan(text, startIdx, opener, closer, idx + 1, depth, inString, escaped = false)
      else if inString then
        if c == '\\' then
          scan(text, startIdx, opener, closer, idx + 1, depth, inString = true, escaped = true)
        else if c == '"' then
          scan(text, startIdx, opener, closer, idx + 1, depth, inString = false, escaped = false)
        else
          scan(text, startIdx, opener, closer, idx + 1, depth, inString = true, escaped = false)
      else if c == '"' then
        scan(text, startIdx, opener, closer, idx + 1, depth, inString = true, escaped = false)
      else if c == opener then
        scan(text, startIdx, opener, closer, idx + 1, depth + 1, inString = false, escaped = false)
      else if c == closer then
        val nextDepth = depth - 1
        if nextDepth == 0 then Some(text.substring(startIdx, idx + 1))
        else scan(text, startIdx, opener, closer, idx + 1, nextDepth, inString = false, escaped = false)
      else
        scan(text, startIdx, opener, closer, idx + 1, depth, inString = false, escaped = false)
