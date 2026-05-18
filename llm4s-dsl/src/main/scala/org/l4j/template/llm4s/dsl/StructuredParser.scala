package org.l4j.template.llm4s.dsl

import cats.MonadThrow
import org.l4j.template.llm4s.structured.StructuredCodec

object StructuredParser:
  def apply[F[_]: MonadThrow, A](using codec: StructuredCodec[A]): Runnable[F, String, A] =
    Runnable.leaf("structured-parser") { (raw, _) =>
      codec.decode(raw) match
        case Right(value) => MonadThrow[F].pure(value)
        case Left(error)  => MonadThrow[F].raiseError(RuntimeException(error))
    }
