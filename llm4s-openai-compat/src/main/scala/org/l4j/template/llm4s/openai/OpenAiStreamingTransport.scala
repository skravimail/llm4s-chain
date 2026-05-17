package org.l4j.template.llm4s.openai

import fs2.Stream
import org.l4j.template.llm4s.core.TraceContext

trait OpenAiStreamingTransport[F[_]]:
  def stream(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): Stream[F, String]

  /** Trace-aware overload. Defaults to the un-traced one. */
  def stream(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
      trace: TraceContext,
  ): Stream[F, String] = stream(path, body, headers)
