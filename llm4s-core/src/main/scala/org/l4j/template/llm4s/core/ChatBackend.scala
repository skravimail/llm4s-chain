package org.l4j.template.llm4s.core

trait ChatBackend[F[_]]:
  def chat(request: ChatRequest): F[ChatResponse]
