package org.llm4s.template.l4j_scala

import dev.langchain4j.data.document.{ Document, Metadata }
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.rag.DefaultRetrievalAugmentor
import dev.langchain4j.rag.RetrievalAugmentor
import dev.langchain4j.rag.content.retriever.{ ContentRetriever, EmbeddingStoreContentRetriever }
import dev.langchain4j.store.embedding.EmbeddingStore
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore

import scala.jdk.CollectionConverters._

/**
 * Scala-friendly helpers for the RAG pieces of langchain4j.
 *
 * The flow is the same as Java: documents -> segments -> embeddings -> store -> retriever -> augmentor.
 * These helpers cut the ceremony for the common case.
 */
object Rag {

  /** Build a `Document` from text, optionally with metadata. */
  def doc(text: String, metadata: Map[String, String] = Map.empty): Document =
    if (metadata.isEmpty) Document.from(text)
    else Document.from(text, new Metadata(metadata.asJava))

  /** Build a `TextSegment` from text, optionally with metadata. */
  def segment(text: String, metadata: Map[String, String] = Map.empty): TextSegment =
    if (metadata.isEmpty) TextSegment.from(text)
    else TextSegment.from(text, new Metadata(metadata.asJava))

  /** In-memory `EmbeddingStore[TextSegment]` — fine for tests and small datasets. */
  def inMemoryStore(): EmbeddingStore[TextSegment] = new InMemoryEmbeddingStore[TextSegment]()

  /**
   * Index a batch of text segments into an embedding store using `embeddingModel`.
   * Returns the list of generated IDs in the same order.
   */
  def index(
    store: EmbeddingStore[TextSegment],
    embeddingModel: EmbeddingModel,
    segments: Seq[TextSegment],
  ): Seq[String] = {
    val jsegments  = segments.asJava
    val embeddings = embeddingModel.embedAll(jsegments).content()
    store.addAll(embeddings, jsegments).asScala.toSeq
  }

  /** Build an `EmbeddingStoreContentRetriever` with sensible defaults; `maxResults` and `minScore` are optional. */
  def retriever(
    store: EmbeddingStore[TextSegment],
    embeddingModel: EmbeddingModel,
    maxResults: Int = 3,
    minScore: Option[Double] = None,
  ): ContentRetriever = {
    val b = EmbeddingStoreContentRetriever
      .builder()
      .embeddingStore(store)
      .embeddingModel(embeddingModel)
      .maxResults(java.lang.Integer.valueOf(maxResults))
    minScore.foreach(s => b.minScore(java.lang.Double.valueOf(s)))
    b.build()
  }

  /** Wrap a content retriever in a `RetrievalAugmentor` for use with `AiServices`. */
  def augmentor(retriever: ContentRetriever): RetrievalAugmentor =
    DefaultRetrievalAugmentor.builder().contentRetriever(retriever).build()

  /** End-to-end convenience: take pre-built segments, return an augmentor ready for `AiService.build`. */
  def buildAugmentor(
    segments: Seq[TextSegment],
    embeddingModel: EmbeddingModel,
    maxResults: Int = 3,
  ): RetrievalAugmentor = {
    val store = inMemoryStore()
    index(store, embeddingModel, segments)
    augmentor(retriever(store, embeddingModel, maxResults))
  }
}
