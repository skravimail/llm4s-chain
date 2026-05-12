package org.l4j.template.llm4s.openai

trait OpenAiTransport[F[_]]:
  def post(
      path: String,
      body: ujson.Value,
      headers: Map[String, String],
  ): F[ujson.Value]
