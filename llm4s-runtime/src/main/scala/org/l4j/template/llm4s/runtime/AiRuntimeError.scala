package org.l4j.template.llm4s.runtime

/** Typed errors raised by the chat runtime.
  *
  * All concrete cases extend `RuntimeException` so they continue to flow through
  * `MonadThrow[F]` without ceremony, but callers can now pattern-match instead
  * of grepping error strings.
  */
sealed abstract class AiRuntimeError(message: String, cause: Throwable)
    extends RuntimeException(message, cause):
  def this(message: String) = this(message, null)

object AiRuntimeError:

  /** Chat loop exceeded the configured tool-call turn budget. */
  final case class MaxTurnsExceeded(maxTurns: Int)
      extends AiRuntimeError(s"AiRuntime chat exceeded $maxTurns tool-call turns")

  /** Provider stopped because of a content-policy filter. */
  case object ContentFiltered
      extends AiRuntimeError("AiRuntime chat aborted: provider returned finishReason=content_filter")

  /** Provider indicated a generic upstream error via `finishReason=error`. */
  final case class ProviderError(detail: Option[String] = None)
      extends AiRuntimeError(
        s"AiRuntime chat aborted: provider returned finishReason=error${detail.fold("")(d => s": $d")}",
      )

  /** Model asked to call a tool that is not registered on the toolkit. */
  final case class ToolMissing(toolName: String)
      extends AiRuntimeError(s"no such tool: $toolName")

  /** A registered tool's executor failed during invocation. */
  final case class ToolFailed(toolName: String, underlying: Throwable)
      extends AiRuntimeError(
        s"tool '$toolName' failed: ${Option(underlying.getMessage).getOrElse(underlying.getClass.getSimpleName)}",
        underlying,
      )
