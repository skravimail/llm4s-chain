package org.l4j.template.llm4s.structured

import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.runtime.ToolKit

/** Per-call overrides for an `AiAgent.chat` invocation.
  *
  * Lets a single agent serve heterogeneous calls — most use the agent's
  * default toolkit and provider settings, but a particular call can swap in
  * a different toolkit, dial temperature down for determinism, or pin a
  * structured response format without spinning up a derived agent.
  *
  * Any field left as `None` falls back to the agent's defaults (and, in turn,
  * to whatever the underlying request already specified).
  */
final case class ChatOptions[F[_]](
    toolKit: Option[ToolKit[F]] = None,
    temperature: Option[Double] = None,
    responseFormat: Option[ResponseFormat] = None,
    metadata: Map[String, String] = Map.empty,
)

object ChatOptions:
  def empty[F[_]]: ChatOptions[F] = ChatOptions[F]()
