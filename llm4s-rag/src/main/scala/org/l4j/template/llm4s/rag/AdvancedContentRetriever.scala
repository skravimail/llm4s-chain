package org.l4j.template.llm4s.rag

import cats.Monad
import cats.syntax.all.*

final class AdvancedContentRetriever[F[_]: Monad](
    queryTransformer: QueryTransformer[F],
    queryRouter: QueryRouter[F],
    contentAggregator: ContentAggregator = ContentAggregator.dedupeByIdKeepBestScore,
    reRanker: ReRanker[F],
    maxResults: Int = 4,
) extends ContentRetriever[F]:

  override def retrieve(query: String): F[List[RetrievedSource]] =
    for
      transformedQueries <- queryTransformer.transform(query)
      routedResults <- transformedQueries.traverse(retrieveForQuery)
      aggregated = contentAggregator.aggregate(routedResults.flatten)
      reranked <- reRanker.rerank(query, aggregated)
    yield reranked.take(maxResults.max(0))

  private def retrieveForQuery(query: String): F[List[RetrievedSource]] =
    queryRouter.route(query).flatMap { retrievers =>
      retrievers.traverse(_.retrieve(query)).map(_.flatten)
    }

object AdvancedContentRetriever:
  def apply[F[_]: Monad](
      queryTransformer: QueryTransformer[F],
      queryRouter: QueryRouter[F],
      reRanker: ReRanker[F],
      maxResults: Int = 4,
      contentAggregator: ContentAggregator = ContentAggregator.dedupeByIdKeepBestScore,
  ): AdvancedContentRetriever[F] =
    new AdvancedContentRetriever[F](
      queryTransformer = queryTransformer,
      queryRouter = queryRouter,
      contentAggregator = contentAggregator,
      reRanker = reRanker,
      maxResults = maxResults,
    )

  def default[F[_]: Monad](
      queryTransformer: QueryTransformer[F],
      queryRouter: QueryRouter[F],
      maxResults: Int = 4,
      contentAggregator: ContentAggregator = ContentAggregator.dedupeByIdKeepBestScore,
  ): AdvancedContentRetriever[F] =
    apply(
      queryTransformer = queryTransformer,
      queryRouter = queryRouter,
      reRanker = ReRanker.identity[F],
      maxResults = maxResults,
      contentAggregator = contentAggregator,
    )
