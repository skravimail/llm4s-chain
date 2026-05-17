package org.l4j.template.llm4s.runtime

/** Re-export of `org.l4j.template.llm4s.core.InvocationContext` so existing
  * callers that import from `runtime` continue to compile after the type was
  * moved into `llm4s-core` in PR-14. */
type InvocationContext = org.l4j.template.llm4s.core.InvocationContext
val InvocationContext = org.l4j.template.llm4s.core.InvocationContext

final case class RuntimeConfig(
    maxTurns: Int = 8,
    toolFailurePolicy: ToolErrorPolicy = ToolErrorPolicy.SurfaceToModel,
    unknownToolPolicy: ToolErrorPolicy = ToolErrorPolicy.FailFast,
)

/** How the chat loop reacts when a tool call fails (executor threw or the
  * model called a name we don't have an executor for).
  *
  * - [[SurfaceToModel]]: wrap the error in a JSON `tool` message and feed it
  *   back to the model on the next turn. Useful when you want the model to
  *   recover from transient failures or unknown tools at runtime.
  * - [[FailFast]]: raise the corresponding `AiRuntimeError.ToolFailed` /
  *   `ToolMissing` immediately. Useful in production when failures should
  *   surface to your error tracking instead of being absorbed.
  * - [[RetryOnce]]: re-invoke the failing tool once, then fall back to
  *   `SurfaceToModel` if it still fails. Only meaningful for executor
  *   failures — for unknown tools the effective behaviour matches
  *   `SurfaceToModel`.
  */
enum ToolErrorPolicy:
  case SurfaceToModel
  case FailFast
  case RetryOnce
