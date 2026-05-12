package org.l4j.template.llm4s.tools

import cats.MonadThrow
import cats.syntax.functor.*
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.runtime.InvocationContext
import org.l4j.template.llm4s.runtime.ToolExecutor
import org.l4j.template.llm4s.runtime.ToolKit

final case class ToolDefinition[F[_]](
    schema: ToolSchema,
    executor: ToolExecutor[F],
):
  def toToolKit: ToolKit[F] =
    ToolKit(
      schemas = List(schema),
      executors = Map(schema.name -> executor),
    )

object ToolDefinition:

  def fromProduct[F[_]: MonadThrow, A](
      name: String,
      description: String,
  )(
      fn: A => F[ToolResult]
  )(using schemaEncoder: SchemaEncoder[A], valueDecoder: ValueDecoder[A]): ToolDefinition[F] =
    val schema = schemaEncoder.schema match
      case objectSchema: JsonSchema.ObjectSchema => objectSchema
      case other =>
        JsonSchema.ObjectSchema(
          properties = Map("value" -> other),
          required = Set("value"),
        )

    ToolDefinition(
      schema = ToolSchema(name, description, schema),
      executor = new ToolExecutor[F]:
        override def execute(
            call: org.l4j.template.llm4s.core.ToolCall,
            context: InvocationContext,
        ): F[ToolResult] =
          valueDecoder.decode(ujson.read(call.argumentsJson)) match
            case Right(args) =>
              fn(args)
            case Left(message) =>
              MonadThrow[F].pure(
                ToolResult.StructuredJson(
                  s"""{"error":"${escape(message)}"}""",
                  isError = true,
                )
              )
    )

  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
