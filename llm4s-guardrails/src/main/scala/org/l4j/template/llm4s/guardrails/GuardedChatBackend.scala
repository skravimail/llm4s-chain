package org.l4j.template.llm4s.guardrails

import cats.MonadThrow
import cats.Parallel
import cats.syntax.all.*
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse

final class GuardedChatBackend[F[_]: MonadThrow: Parallel](
    underlying: ChatBackend[F],
    guardrails: GuardrailChain[F],
    retryPolicy: RetryPolicy = RetryPolicy.noRetry,
) extends ChatBackend[F]:

  override def chat(request: ChatRequest): F[ChatResponse] =
    retryPolicy.run {
      for
        checkedRequest <- guardrails.checkInput(request)
        response <- underlying.chat(checkedRequest)
        checkedResponse <- guardrails.checkOutput(checkedRequest, response)
      yield checkedResponse
    }
