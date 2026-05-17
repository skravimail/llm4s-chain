package org.l4j.template.llm4s.openai

import org.l4j.template.llm4s.core.TraceContext

trait OpenAiTransport[F[_]]:
  def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): F[ujson.Value]

  /** Trace-aware overload. Defaults to the un-traced one so existing
    * transports keep compiling; trace-aware transports
    * (`SttpOpenAiTransport`) override it to fire `HttpListener` events
    * correlated to the chat's `TraceContext`. */
  def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
      trace: TraceContext,
  ): F[ujson.Value] = post(path, body, headers)
