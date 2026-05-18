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

  test("retrieval query supports namespace and metadata filtering") {
    val program = for
      store <- InMemoryEmbeddingStore.create[IO]
      _ <- store.add(
        List(
          EmbeddingRecord(
            "scala-guide",
            "Scala 3 guide",
            EmbeddingVector.of(1.0, 0.0),
            metadata = Map("kind" -> "guide", "lang" -> "scala"),
            namespace = Some("docs"),
          ),
          EmbeddingRecord(
            "scala-api",
            "Scala API notes",
            EmbeddingVector.of(0.9, 0.1),
            metadata = Map("kind" -> "api", "lang" -> "scala"),
            namespace = Some("docs"),
          ),
          EmbeddingRecord(
            "java-guide",
            "Java guide",
            EmbeddingVector.of(0.95, 0.05),
            metadata = Map("kind" -> "guide", "lang" -> "java"),
            namespace = Some("code"),
          ),
        )
      )
      results <- store.search(
        RetrievalQuery(
          vector = EmbeddingVector.of(1.0, 0.0),
          maxResults = 3,
          namespace = Some("docs"),
          filter = Some(
            MetadataFilter.And(
              List(
                MetadataFilter.Eq("kind", "guide"),
                MetadataFilter.In("lang", Set("scala", "kotlin")),
              )
            )
          ),
        )
      )
    yield results

    val results = program.unsafeRunSync()

    assertEquals(results.map(source => source.id -> source.namespace), List("scala-guide" -> Some("docs")))
  }

  test("embedding content retriever can scope retrieval by namespace and filter") {
    val program = for
      store <- InMemoryEmbeddingStore.create[IO]
      _ <- store.add(
        List(
          EmbeddingRecord(
            "scala-guide",
            "Scala guide",
            EmbeddingVector.of(1.0, 0.0),
            metadata = Map("kind" -> "guide"),
            namespace = Some("docs"),
          ),
          EmbeddingRecord(
            "scala-api",
            "Scala API",
            EmbeddingVector.of(0.99, 0.01),
            metadata = Map("kind" -> "api"),
            namespace = Some("docs"),
          ),
          EmbeddingRecord(
            "scala-guide-code",
            "Scala code guide",
            EmbeddingVector.of(0.98, 0.02),
            metadata = Map("kind" -> "guide"),
            namespace = Some("code"),
          ),
        )
      )
      retriever = EmbeddingContentRetriever[IO](
        embeddingModel = StaticEmbeddingModel(Map("scala" -> EmbeddingVector.of(1.0, 0.0))),
        embeddingStore = store,
        maxResults = 5,
        namespace = Some("docs"),
        filter = Some(MetadataFilter.Eq("kind", "guide")),
      )
      results <- retriever.retrieve("scala")
    yield results

    assertEquals(program.unsafeRunSync().map(_.id), List("scala-guide"))
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
    assert(PgVectorConfig("embeddings", namespaceColumn = "namespace; drop table users").validated.isLeft)
  }

  test("content aggregator deduplicates sources by id and keeps the best score") {
    val aggregated = ContentAggregator.dedupeByIdKeepBestScore.aggregate(
      List(
        RetrievedSource("a", "lower", score = 0.2),
        RetrievedSource("b", "middle", score = 0.5),
        RetrievedSource("a", "higher", score = 0.9),
      )
    )

    assertEquals(aggregated.map(source => source.id -> source.text), List("a" -> "higher", "b" -> "middle"))
  }

  test("advanced content retriever transforms routes aggregates and reranks results") {
    val primaryRetriever = StaticRetriever(
      Map(
        "scala" -> List(
          RetrievedSource("lang", "Scala is strongly typed", score = 0.4, metadata = Map("rank" -> "3")),
          RetrievedSource("shared", "Old shared result", score = 0.2, metadata = Map("rank" -> "9")),
        ),
        "scala effects" -> List(
          RetrievedSource("shared", "Best shared result", score = 0.95, metadata = Map("rank" -> "2"))
        ),
      )
    )
    val secondaryRetriever = StaticRetriever(
      Map(
        "scala" -> List(
          RetrievedSource("runtime", "Cats Effect powers runtime composition", score = 0.7, metadata = Map("rank" -> "1"))
        )
      )
    )

    val retriever = AdvancedContentRetriever[IO](
      queryTransformer = QueryTransformer.static[IO](query => List(query, s"$query effects")),
      queryRouter = QueryRouter.static[IO](List(primaryRetriever, secondaryRetriever)),
      reRanker = MetadataRanker,
      maxResults = 2,
    )

    val results = retriever.retrieve("scala").unsafeRunSync()

    assertEquals(results.map(_.id), List("runtime", "shared"))
    assertEquals(results.find(_.id == "shared").map(_.text), Some("Best shared result"))
  }

private final class StaticEmbeddingModel(values: Map[String, EmbeddingVector]) extends EmbeddingModel[IO]:
  override def embed(text: String): IO[EmbeddingVector] =
    IO.fromOption(values.get(text))(IllegalArgumentException(s"No embedding for: $text"))

private final class StaticRetriever(results: Map[String, List[RetrievedSource]]) extends ContentRetriever[IO]:
  override def retrieve(query: String): IO[List[RetrievedSource]] =
    IO.pure(results.getOrElse(query, Nil))

private object MetadataRanker extends ReRanker[IO]:
  override def rerank(query: String, sources: List[RetrievedSource]): IO[List[RetrievedSource]] =
    IO.pure(sources.sortBy(_.metadata.get("rank").flatMap(_.toIntOption).getOrElse(Int.MaxValue)))
