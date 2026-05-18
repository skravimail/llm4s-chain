package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.rag.ContentRetriever
import org.l4j.template.llm4s.rag.DefaultRetrievalAugmentor
import org.l4j.template.llm4s.rag.RetrievalAugmentor
import org.l4j.template.llm4s.rag.RetrievedSource
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener

class RagDslSpec extends FunSuite:

  test("content retriever runnable adapts a retriever directly") {
    val retriever = new ContentRetriever[IO]:
      override def retrieve(query: String): IO[List[RetrievedSource]] =
        IO.pure(List(RetrievedSource("doc-1", s"context:$query", score = 0.9)))

    val results = ContentRetrieverRunnable[IO](retriever).run("scala", stubContext).unsafeRunSync()

    assertEquals(results.map(_.id), List("doc-1"))
    assertEquals(results.head.text, "context:scala")
  }

  test("retrieval augmentor runnable exposes augmented request and sources") {
    val retriever = new ContentRetriever[IO]:
      override def retrieve(query: String): IO[List[RetrievedSource]] =
        IO.pure(List(RetrievedSource("doc-1", s"about:$query", score = 0.95)))

    val augmentor = DefaultRetrievalAugmentor[IO](retriever)
    val request = ChatRequest(
      messages = List(ChatMessage.UserMessage.from("Explain givens"))
    )

    val augmented = RetrievalAugmentorRunnable[IO](augmentor).run(request, stubContext).unsafeRunSync()

    assertEquals(augmented.sources.map(_.id), List("doc-1"))
    assert(augmented.request.messages.last.text.contains("[source:doc-1 score:0.9500]"))
    assert(augmented.request.messages.last.text.contains("User request:\nExplain givens"))
  }

  test("retrieval-augmented chain composes through AiAgent runnable") {
    val retriever = new ContentRetriever[IO]:
      override def retrieve(query: String): IO[List[RetrievedSource]] =
        IO.pure(
          List(
            RetrievedSource("doc-1", s"facts for $query", score = 0.91),
            RetrievedSource("doc-2", "secondary note", score = 0.61),
          )
        )

    val augmentor: RetrievalAugmentor[IO] = DefaultRetrievalAugmentor[IO](retriever)

    val chain =
      PromptTemplate.user[IO, String](system = Some("Answer using retrieved context when relevant."))(identity) andThen
        RetrievalAugmentorRunnable[IO](augmentor).map(_.request) andThen
        AiAgentRunnable.fromContext[IO]()

    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.pure(ChatResponse(ChatMessage.AiMessage.from(request.messages.last.text)))

    val context = RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = RuntimeListener.noop[IO],
    )

    val result = chain.run("opaque types", context).unsafeRunSync()

    assert(result.contains("[source:doc-1 score:0.9100]"))
    assert(result.contains("facts for opaque types"))
    assert(result.contains("User request:\nopaque types"))
  }

  private def stubContext: RunContext[IO] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.pure(ChatResponse(ChatMessage.AiMessage.from(request.messages.last.text)))

    RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = RuntimeListener.noop[IO],
    )
