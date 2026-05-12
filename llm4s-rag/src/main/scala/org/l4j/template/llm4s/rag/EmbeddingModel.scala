package org.l4j.template.llm4s.rag

import cats.Applicative
import cats.syntax.all.*

trait EmbeddingModel[F[_]]:
  def embed(text: String): F[EmbeddingVector]

  def embedAll(texts: List[String])(using Applicative[F]): F[List[EmbeddingVector]] =
    texts.traverse(embed)

