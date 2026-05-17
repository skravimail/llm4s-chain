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
    build(name, description, schemaEncoder.schema, valueDecoder, fn)

  /** Build a tool from any type that has a `ToolDef[A]` — typically obtained
    * via `case class Args(...) derives ToolDef`.
    *
    * One context bound instead of two means schema and decoder cannot drift
    * apart at the call site.
    */
  def fromArgs[F[_]: MonadThrow, A](
      name: String,
      description: String,
  )(
      fn: A => F[ToolResult]
  )(using toolDef: ToolDef[A]): ToolDefinition[F] =
    build(name, description, toolDef.schema, toolDef.decoder, fn)

  private def build[F[_]: MonadThrow, A](
      name: String,
      description: String,
      rawSchema: JsonSchema,
      decoder: ValueDecoder[A],
      fn: A => F[ToolResult],
  ): ToolDefinition[F] =
    val schema = rawSchema match
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
          decoder.decode(ujson.read(call.argumentsJson)) match
            case Right(args) =>
              fn(args)
            case Left(message) =>
              MonadThrow[F].pure(
                ToolResult.StructuredJson(
                  ujson.write(ujson.Obj("error" -> message)),
                  isError = true,
                )
              )
    )
