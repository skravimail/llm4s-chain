package org.l4j.template.llm4s.runtime

import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ToolCall

final case class InvocationContext(
    turn: Int,
    request: ChatRequest,
    toolCall: ToolCall,
)

final case class RuntimeConfig(
    maxTurns: Int = 8
)
