package org.l4j.template.llm4s.dsl

import cats.Functor
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.rag.AugmentedChatRequest
import org.l4j.template.llm4s.rag.RetrievalAugmentor

object RetrievalAugmentorRunnable:
  def apply[F[_]: Functor](
      augmentor: RetrievalAugmentor[F],
  ): Runnable[F, ChatRequest, AugmentedChatRequest] =
    Runnable.eval { (request, _) =>
      augmentor.augment(request)
    }
