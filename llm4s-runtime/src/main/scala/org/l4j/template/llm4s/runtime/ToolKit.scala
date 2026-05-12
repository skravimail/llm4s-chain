package org.l4j.template.llm4s.runtime

import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema

trait ToolExecutor[F[_]]:
  def execute(call: ToolCall, context: InvocationContext): F[ToolResult]

final case class ToolKit[F[_]](
    schemas: List[ToolSchema],
    executors: Map[String, ToolExecutor[F]],
):
  def ++(other: ToolKit[F]): ToolKit[F] =
    ToolKit(schemas ++ other.schemas, executors ++ other.executors)

object ToolKit:
  def empty[F[_]]: ToolKit[F] =
    ToolKit[F](Nil, Map.empty)
