package org.l4j.template.llm4s.dsl

import cats.Functor
import org.l4j.template.llm4s.rag.ContentRetriever
import org.l4j.template.llm4s.rag.RetrievedSource

object ContentRetrieverRunnable:
  def apply[F[_]: Functor](
      retriever: ContentRetriever[F],
  ): Runnable[F, String, List[RetrievedSource]] =
    Runnable.leaf("content-retriever") { (query, _) =>
      retriever.retrieve(query)
    }
