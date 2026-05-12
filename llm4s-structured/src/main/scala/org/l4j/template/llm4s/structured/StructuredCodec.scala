package org.l4j.template.llm4s.structured

import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

import scala.compiletime.constValue
import scala.deriving.Mirror

trait StructuredCodec[A]:
  def schemaName: String
  def schema: JsonSchema
  def decode(raw: String): Either[String, A]

object StructuredCodec:

  final class DerivedStructuredCodec[A](
      override val schemaName: String,
      schemaEncoder: SchemaEncoder[A],
      valueDecoder: ValueDecoder[A],
  ) extends StructuredCodec[A]:
    override val schema: JsonSchema = schemaEncoder.schema
    override def decode(raw: String): Either[String, A] =
      try valueDecoder.decode(ujson.read(raw))
      catch case error: Throwable => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  inline given derived[A](using
      mirror: Mirror.Of[A],
      schemaEncoder: SchemaEncoder[A],
      valueDecoder: ValueDecoder[A],
  ): StructuredCodec[A] =
    DerivedStructuredCodec[A](
      constValue[mirror.MirroredLabel].toString,
      schemaEncoder,
      valueDecoder,
    )
