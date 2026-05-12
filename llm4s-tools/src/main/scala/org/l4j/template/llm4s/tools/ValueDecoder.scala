package org.l4j.template.llm4s.tools

import scala.compiletime.{ constValue, erasedValue, summonInline }
import scala.deriving.Mirror

trait ValueDecoder[A]:
  def decode(value: ujson.Value): Either[String, A]
  def decodeMissing: Either[String, A] =
    Left("missing value")

object ValueDecoder:

  given ValueDecoder[String] with
    override def decode(value: ujson.Value): Either[String, String] =
      value match
        case ujson.Str(text) => Right(text)
        case other           => Left(s"expected string, got ${other.getClass.getSimpleName}")

  given ValueDecoder[Int] with
    override def decode(value: ujson.Value): Either[String, Int] =
      value match
        case ujson.Num(number) => Right(number.toInt)
        case other             => Left(s"expected integer, got ${other.getClass.getSimpleName}")

  given ValueDecoder[Double] with
    override def decode(value: ujson.Value): Either[String, Double] =
      value match
        case ujson.Num(number) => Right(number)
        case other             => Left(s"expected number, got ${other.getClass.getSimpleName}")

  given ValueDecoder[Boolean] with
    override def decode(value: ujson.Value): Either[String, Boolean] =
      value match
        case ujson.Bool(flag) => Right(flag)
        case other            => Left(s"expected boolean, got ${other.getClass.getSimpleName}")

  given [A](using decoder: ValueDecoder[A]): ValueDecoder[Option[A]] with
    override def decode(value: ujson.Value): Either[String, Option[A]] =
      value match
        case ujson.Null => Right(None)
        case other      => decoder.decode(other).map(Some(_))

    override def decodeMissing: Either[String, Option[A]] =
      Right(None)

  given [A](using decoder: ValueDecoder[A]): ValueDecoder[List[A]] with
    override def decode(value: ujson.Value): Either[String, List[A]] =
      value match
        case ujson.Arr(values) =>
          values.toList.zipWithIndex.foldLeft(Right(List.empty[A]): Either[String, List[A]]) {
            case (acc, (item, index)) =>
              for
                decoded <- decoder.decode(item).left.map(message => s"[$index] $message")
                current <- acc
              yield current :+ decoded
          }
        case other =>
          Left(s"expected array, got ${other.getClass.getSimpleName}")

  inline given derived[A](using mirror: Mirror.Of[A]): ValueDecoder[A] =
    inline mirror match
      case product: Mirror.ProductOf[A] => productDecoder(product)
      case sum: Mirror.SumOf[A]         => enumDecoder(sum)

  final class ProductValueDecoder[A](
      product: Mirror.ProductOf[A],
      labels: List[String],
      decoders: List[ValueDecoder[?]],
  ) extends ValueDecoder[A]:
    override def decode(value: ujson.Value): Either[String, A] =
      value match
        case ujson.Obj(fields) =>
          decodeElements(fields, labels.zip(decoders)).map { values =>
            product.fromProduct(Tuple.fromArray(values.toArray))
          }
        case other =>
          Left(s"expected object, got ${other.getClass.getSimpleName}")

  final class EnumValueDecoder[A](
      labels: List[String],
      values: List[Any],
  ) extends ValueDecoder[A]:
    override def decode(value: ujson.Value): Either[String, A] =
      value match
        case ujson.Str(label) =>
          labels.indexOf(label) match
            case -1    => Left(s"expected one of ${labels.mkString(", ")}, got $label")
            case index => Right(values(index).asInstanceOf[A])
        case other =>
          Left(s"expected enum label, got ${other.getClass.getSimpleName}")

  private inline def productDecoder[A](product: Mirror.ProductOf[A]): ValueDecoder[A] =
    ProductValueDecoder[A](
      product,
      labelsOf[product.MirroredElemLabels],
      decodersOf[product.MirroredElemTypes],
    )

  private inline def enumDecoder[A](sum: Mirror.SumOf[A]): ValueDecoder[A] =
    EnumValueDecoder[A](
      labelsOf[sum.MirroredElemLabels],
      valuesOf[sum.MirroredElemTypes],
    )

  private def decodeElements(
      fields: collection.Map[String, ujson.Value],
      entries: List[(String, ValueDecoder[?])],
  ): Either[String, List[Any]] =
    entries.foldLeft(Right(List.empty[Any]): Either[String, List[Any]]) {
      case (acc, (label, decoder)) =>
        for
          current <- acc
          decoded <- fields.get(label) match
            case Some(value) =>
              decoder.asInstanceOf[ValueDecoder[Any]].decode(value).left.map(message => s"$label: $message")
            case None =>
              decoder.asInstanceOf[ValueDecoder[Any]].decodeMissing.left.map(message => s"$label: $message")
        yield current :+ decoded
    }

  private inline def decodersOf[Elems <: Tuple]: List[ValueDecoder[?]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        summonInline[ValueDecoder[head]] :: decodersOf[tail]

  private inline def labelsOf[Labels <: Tuple]: List[String] =
    inline erasedValue[Labels] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        constValue[head].asInstanceOf[String] :: labelsOf[tail]

  private inline def valuesOf[Elems <: Tuple]: List[Any] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        summonInline[ValueOf[head]].value :: valuesOf[tail]
