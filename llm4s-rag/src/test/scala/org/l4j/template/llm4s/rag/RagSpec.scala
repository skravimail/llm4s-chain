package org.l4j.template.llm4s.rag

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest

class RagSpec extends FunSuite:

  test("in-memory embedding store ranks by cosine similarity") {
    val program = for
      store <- InMemoryEmbeddingStore.create[IO]
      _ <- store.add(
        List(
          EmbeddingRecord("scala", "Scala is a typed language", EmbeddingVector.of(1.0, 0.0)),
          EmbeddingRecord("java", "Java runs on the JVM", EmbeddingVector.of(0.2, 0.8)),
          EmbeddingRecord("cats", "Cats Effect models effects", EmbeddingVector.of(0.8, 0.2)),
        )
      )
      results <- store.search(EmbeddingVector.of(1.0, 0.0), maxResults = 2)
    yield results

    val results = program.unsafeRunSync()

    assertEquals(results.map(_.id), List("scala", "cats"))
    assert(results.head.score > results(1).score)
  }

  test("embedding content retriever embeds the query before searching") {
    val program = for
      store <- InMemoryEmbeddingStore.create[IO]
      _ <- store.add(
        List(
          EmbeddingRecord("weather", "Weather tools return forecasts", EmbeddingVector.of(0.0, 1.0)),
          EmbeddingRecord("math", "Math tools calculate expressions", EmbeddingVector.of(1.0, 0.0)),
        )
      )
      retriever = EmbeddingContentRetriever[IO](
        embeddingModel = StaticEmbeddingModel(Map("forecast" -> EmbeddingVector.of(0.0, 1.0))),
        embeddingStore = store,
        maxResults = 1,
      )
      results <- retriever.retrieve("forecast")
    yield results

    assertEquals(program.unsafeRunSync().map(_.id), List("weather"))
  }

  test("retrieval augmentor rewrites the last user message and returns sources") {
    val retriever = new ContentRetriever[IO]:
      override def retrieve(query: String): IO[List[RetrievedSource]] =
        IO.pure(
          List(
            RetrievedSource("doc-1", "Scala 3 supports inline macros.", score = 0.91)
          )
        )

    val request = ChatRequest(
      messages = List(
        ChatMessage.SystemMessage.from("Answer precisely."),
        ChatMessage.UserMessage.from("How do Scala macros work?"),
      )
    )

    val augmented = DefaultRetrievalAugmentor[IO](retriever).augment(request).unsafeRunSync()

    assertEquals(augmented.sources.map(_.id), List("doc-1"))
    assert(augmented.request.messages.last.text.contains("[source:doc-1 score:0.9100]"))
    assert(augmented.request.messages.last.text.contains("User request:\nHow do Scala macros work?"))
  }

  test("pgvector config rejects unsafe identifiers") {
    assertEquals(PgVectorConfig("public.embeddings").validated, Right(PgVectorConfig("public.embeddings")))
    assert(PgVectorConfig("embeddings; drop table users").validated.isLeft)
    assert(PgVectorConfig("embeddings", idColumn = "id or 1=1").validated.isLeft)
  }

private final class StaticEmbeddingModel(values: Map[String, EmbeddingVector]) extends EmbeddingModel[IO]:
  override def embed(text: String): IO[EmbeddingVector] =
    IO.fromOption(values.get(text))(IllegalArgumentException(s"No embedding for: $text"))

