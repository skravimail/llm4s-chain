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

  inline given derived[A](using
      mirror: Mirror.Of[A],
      schemaEncoder: SchemaEncoder[A],
      valueDecoder: ValueDecoder[A],
  ): StructuredCodec[A] =
    new StructuredCodec[A]:
      override val schemaName: String = constValue[mirror.MirroredLabel].toString
      override val schema: JsonSchema = schemaEncoder.schema
      override def decode(raw: String): Either[String, A] =
        try valueDecoder.decode(ujson.read(raw))
        catch case error: Throwable => Left(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))
