package org.l4j.template.llm4s.rag

import cats.Applicative

trait QueryTransformer[F[_]]:
  def transform(query: String): F[List[String]]

object QueryTransformer:
  def identity[F[_]: Applicative]: QueryTransformer[F] =
    new QueryTransformer[F]:
      override def transform(query: String): F[List[String]] =
        Applicative[F].pure(List(query))

  def static[F[_]: Applicative](queries: String => List[String]): QueryTransformer[F] =
    new QueryTransformer[F]:
      override def transform(query: String): F[List[String]] =
        Applicative[F].pure(queries(query))

