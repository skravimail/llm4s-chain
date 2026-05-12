package org.l4j.template.llm4s.mcp

import cats.MonadThrow
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.ToolExecutor
import org.l4j.template.llm4s.runtime.ToolKit

final class McpToolProvider[F[_]: MonadThrow](
    client: McpClient[F]
):

  def toolKit: F[ToolKit[F]] =
    client.listAllTools.map { tools =>
      val schemas = tools.map(toToolSchema)
      val executors = tools.map(tool => tool.name -> McpToolExecutor(client, tool.name)).toMap
      ToolKit(schemas, executors)
    }

  private def toToolSchema(tool: McpTool): ToolSchema =
    ToolSchema(
      name = tool.name,
      description = tool.description,
      parameters = McpSchemaConverter.toObjectSchema(tool.inputSchema),
    )

private final case class McpToolExecutor[F[_]: MonadThrow](
    client: McpClient[F],
    toolName: String,
) extends ToolExecutor[F]:
  override def execute(call: ToolCall, context: InvocationContext): F[ToolResult] =
    client.callTool(toolName, call.argumentsJson).map(_.toToolResult)

