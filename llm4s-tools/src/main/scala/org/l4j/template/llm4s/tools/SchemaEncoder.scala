package org.l4j.template.llm4s.tools

import org.l4j.template.llm4s.core.JsonSchema

import scala.compiletime.{ constValue, erasedValue, summonInline }
import scala.deriving.Mirror

trait SchemaEncoder[A]:
  def schema: JsonSchema
  def isOptional: Boolean = false

object SchemaEncoder:

  given SchemaEncoder[String] with
    override val schema: JsonSchema = JsonSchema.StringSchema()

  given SchemaEncoder[Int] with
    override val schema: JsonSchema = JsonSchema.IntegerSchema()

  given SchemaEncoder[Double] with
    override val schema: JsonSchema = JsonSchema.NumberSchema()

  given SchemaEncoder[Boolean] with
    override val schema: JsonSchema = JsonSchema.BooleanSchema()

  given [A](using encoder: SchemaEncoder[A]): SchemaEncoder[Option[A]] with
    override def schema: JsonSchema = encoder.schema
    override def isOptional: Boolean = true

  given [A](using encoder: SchemaEncoder[A]): SchemaEncoder[List[A]] with
    override def schema: JsonSchema = JsonSchema.ArraySchema(encoder.schema)

  inline given derived[A](using mirror: Mirror.Of[A]): SchemaEncoder[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => productEncoder(product)
      case sum: Mirror.SumOf[A]         => enumEncoder(sum)

  final class ProductSchemaEncoder[A](
      labels: List[String],
      encoders: List[SchemaEncoder[?]],
  ) extends SchemaEncoder[A]:
    override val schema: JsonSchema =
      JsonSchema.ObjectSchema(
        properties = labels.zip(encoders).map { case (label, encoder) => label -> encoder.schema }.toMap,
        required = labels.zip(encoders).collect {
          case (label, encoder) if !encoder.isOptional => label
        }.toSet,
      )

  final class EnumSchemaEncoder[A](labels: List[String]) extends SchemaEncoder[A]:
    override val schema: JsonSchema = JsonSchema.EnumSchema(labels)

  private inline def productEncoder[A](product: Mirror.ProductOf[A]): SchemaEncoder[A] =
    ProductSchemaEncoder[A](
      labelsOf[product.MirroredElemLabels],
      encodersOf[product.MirroredElemTypes],
    )

  private inline def enumEncoder[A](sum: Mirror.SumOf[A]): SchemaEncoder[A] =
    EnumSchemaEncoder[A](labelsOf[sum.MirroredElemLabels])

  private inline def encodersOf[Elems <: Tuple]: List[SchemaEncoder[?]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        summonInline[SchemaEncoder[head]] :: encodersOf[tail]

  private inline def labelsOf[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        constValue[head].asInstanceOf[String] :: labelsOf[tail]
