package org.l4j.template.llm4s.rag

import cats.effect.Sync
import java.sql.Connection
import java.sql.ResultSet
import scala.util.Try
import javax.sql.DataSource
import upickle.default.*

final case class PgVectorConfig(
    table: String,
    idColumn: String = "id",
    textColumn: String = "text",
    embeddingColumn: String = "embedding",
    metadataColumn: String = "metadata",
    namespaceColumn: String = "namespace",
):
  def validated: Either[String, PgVectorConfig] =
    val identifiers = List(table, idColumn, textColumn, embeddingColumn, metadataColumn, namespaceColumn)
    identifiers
      .find(identifier => !PgVectorConfig.isSafeQualifiedIdentifier(identifier))
      .fold[Either[String, PgVectorConfig]](Right(this))(bad => Left(s"Unsafe SQL identifier: $bad"))

object PgVectorConfig:
  private val Identifier = "^[A-Za-z_][A-Za-z0-9_]*$".r

  def isSafeQualifiedIdentifier(value: String): Boolean =
    value.split("\\.").toList match
      case Nil   => false
      case parts => parts.forall(part => Identifier.matches(part))

final class PgVectorEmbeddingStore[F[_]: Sync] private (
    dataSource: DataSource,
    safeConfig: PgVectorConfig,
) extends EmbeddingStore[F]:

  private val EmptyNamespace = ""

  override def add(records: List[EmbeddingRecord]): F[Unit] =
    Sync[F].blocking {
      withConnection { connection =>
        val sql =
          s"""insert into ${safeConfig.table}
             |(${safeConfig.namespaceColumn}, ${safeConfig.idColumn}, ${safeConfig.textColumn}, ${safeConfig.embeddingColumn}, ${safeConfig.metadataColumn})
             |values (?, ?, ?, ?::vector, ?::jsonb)
             |on conflict (${safeConfig.namespaceColumn}, ${safeConfig.idColumn}) do update set
             |${safeConfig.textColumn} = excluded.${safeConfig.textColumn},
             |${safeConfig.embeddingColumn} = excluded.${safeConfig.embeddingColumn},
             |${safeConfig.metadataColumn} = excluded.${safeConfig.metadataColumn}
             |""".stripMargin

        val statement = connection.prepareStatement(sql)
        try
          records.foreach { record =>
            statement.setString(1, encodeNamespace(record.namespace))
            statement.setString(2, record.id)
            statement.setString(3, record.text)
            statement.setString(4, record.embedding.toPgVectorLiteral)
            statement.setString(5, write(record.metadata))
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }
    }

  override def search(query: RetrievalQuery): F[List[RetrievedSource]] =
    Sync[F].blocking {
      withConnection { connection =>
        val whereClauses = List.newBuilder[String]
        val parameters = List.newBuilder[String]

        query.namespace.foreach { namespace =>
          whereClauses += s"${safeConfig.namespaceColumn} = ?"
          parameters += encodeNamespace(Some(namespace))
        }

        query.filter.foreach { filter =>
          val compiled = compileFilter(filter)
          whereClauses += compiled.sql
          parameters ++= compiled.parameters
        }

        val whereSql =
          val clauses = whereClauses.result()
          if clauses.isEmpty then ""
          else clauses.mkString("where ", " and ", "\n")

        val sql =
          s"""select ${safeConfig.idColumn}, ${safeConfig.textColumn}, ${safeConfig.metadataColumn}::text,
             |${safeConfig.namespaceColumn},
             |1 - (${safeConfig.embeddingColumn} <=> ?::vector) as score
             |from ${safeConfig.table}
             |$whereSql
             |order by ${safeConfig.embeddingColumn} <=> ?::vector
             |limit ?
             |""".stripMargin

        val statement = connection.prepareStatement(sql)
        try
          val vector = query.vector.toPgVectorLiteral
          statement.setString(1, vector)
          parameters.result().zipWithIndex.foreach { case (value, index) =>
            statement.setString(index + 2, value)
          }
          val trailingIndex = parameters.result().size + 2
          statement.setString(trailingIndex, vector)
          statement.setInt(trailingIndex + 1, query.maxResults.max(0))
          val resultSet = statement.executeQuery()
          try
            resultSet.toRetrievedSources
              .filter(source => query.minScore.forall(source.score >= _))
          finally resultSet.close()
        finally statement.close()
      }
    }

  override def remove(ids: Set[String]): F[Unit] =
    Sync[F].blocking {
      if ids.nonEmpty then
        withConnection { connection =>
          val placeholders = ids.map(_ => "?").mkString(", ")
          val sql = s"delete from ${safeConfig.table} where ${safeConfig.idColumn} in ($placeholders)"
          val statement = connection.prepareStatement(sql)
          try
            ids.toList.zipWithIndex.foreach { case (id, index) =>
              statement.setString(index + 1, id)
            }
            statement.executeUpdate()
            ()
          finally statement.close()
        }
    }

  def initialize: F[Unit] =
    Sync[F].blocking {
      withConnection { connection =>
        val sql =
          s"""create table if not exists ${safeConfig.table} (
             |${safeConfig.namespaceColumn} text not null default '$EmptyNamespace',
             |${safeConfig.idColumn} text not null,
             |${safeConfig.textColumn} text not null,
             |${safeConfig.embeddingColumn} vector not null,
             |${safeConfig.metadataColumn} jsonb not null default '{}'::jsonb,
             |primary key (${safeConfig.namespaceColumn}, ${safeConfig.idColumn})
             |)
             |""".stripMargin
        val statement = connection.createStatement()
        try
          statement.execute(sql)
          ()
        finally statement.close()
      }
    }

  private def withConnection[A](use: Connection => A): A =
    val connection = dataSource.getConnection
    try use(connection)
    finally connection.close()

  extension (resultSet: ResultSet)
    private def toRetrievedSources: List[RetrievedSource] =
      val builder = List.newBuilder[RetrievedSource]
      while resultSet.next() do
        builder += RetrievedSource(
          id = resultSet.getString(1),
          text = resultSet.getString(2),
          metadata = parseMetadata(resultSet.getString(3)),
          namespace = decodeNamespace(resultSet.getString(4)),
          score = resultSet.getDouble(5),
        )
      builder.result()

  private def parseMetadata(raw: String | Null): Map[String, String] =
    Option(raw).flatMap { value =>
      Try(read[Map[String, String]](value)).toOption
    }.getOrElse(Map.empty)

  private def encodeNamespace(namespace: Option[String]): String =
    namespace.filter(_.nonEmpty).getOrElse(EmptyNamespace)

  private def decodeNamespace(raw: String | Null): Option[String] =
    Option(raw).map(_.trim).filter(_.nonEmpty)

  private def compileFilter(filter: MetadataFilter): CompiledFilter =
    filter match
      case MetadataFilter.Eq(key, value) =>
        CompiledFilter(s"(${safeConfig.metadataColumn} ->> ?) = ?", List(key, value))
      case MetadataFilter.In(key, values) =>
        val sortedValues = values.toList.sorted
        if sortedValues.isEmpty then CompiledFilter("1 = 0", Nil)
        else
          val clauses = sortedValues.map(_ => s"(${safeConfig.metadataColumn} ->> ?) = ?")
          val parameters = sortedValues.flatMap(value => List(key, value))
          CompiledFilter(clauses.mkString("(", " or ", ")"), parameters)
      case MetadataFilter.And(filters) =>
        compileCompositeFilter(filters, connective = "and", emptyClause = "1 = 1")
      case MetadataFilter.Or(filters) =>
        compileCompositeFilter(filters, connective = "or", emptyClause = "1 = 0")

  private def compileCompositeFilter(
      filters: List[MetadataFilter],
      connective: String,
      emptyClause: String,
  ): CompiledFilter =
    if filters.isEmpty then CompiledFilter(emptyClause, Nil)
    else
      val compiled = filters.map(compileFilter)
      CompiledFilter(
        compiled.map(_.sql).mkString("(", s" $connective ", ")"),
        compiled.flatMap(_.parameters),
      )

  private final case class CompiledFilter(sql: String, parameters: List[String])

object PgVectorEmbeddingStore:
  def create[F[_]: Sync](
      dataSource: DataSource,
      config: PgVectorConfig,
  ): F[Either[String, PgVectorEmbeddingStore[F]]] =
    Sync[F].pure(config.validated.map(new PgVectorEmbeddingStore[F](dataSource, _)))
