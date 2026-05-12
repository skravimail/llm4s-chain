package org.l4j.template.llm4s.mcp

trait McpTransport[F[_]]:
  def request(method: String, params: Option[ujson.Value]): F[ujson.Value]

