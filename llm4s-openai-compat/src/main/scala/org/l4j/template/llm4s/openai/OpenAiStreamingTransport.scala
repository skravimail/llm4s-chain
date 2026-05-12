package org.l4j.template.llm4s.openai

import fs2.Stream

trait OpenAiStreamingTransport[F[_]]:
  def stream(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): Stream[F, String]
