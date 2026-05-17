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

    /** Decode the raw model output into `A`.
      *
      * PR-20: routes the response through [[JsonExtractor]] first, so a
      * chatty model that wraps its JSON in a markdown fence or prefixes
      * it with prose still decodes successfully. Strict-schema servers
      * pay zero overhead (the fast path is `ujson.read` on the trimmed
      * input). */
    override def decode(raw: String): Either[String, A] =
      JsonExtractor.extract(raw).flatMap(valueDecoder.decode)

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
