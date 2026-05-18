package org.l4j.template.llm4s.dsl

import cats.Applicative
import org.l4j.template.llm4s.core.ChatResponse

object TextOutput:
  def apply[F[_]: Applicative]: Runnable[F, ChatResponse, String] =
    Runnable.leaf("text-output") { (response, _) =>
      Applicative[F].pure(response.text)
    }
