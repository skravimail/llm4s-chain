package org.l4j.template.llm4s.rag

import cats.effect.Sync
import cats.syntax.all.*
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource
import upickle.default.*

final case class PgVectorConfig(
    table: String,
    idColumn: String = "id",
    textColumn: String = "text",
    embeddingColumn: String = "embedding",
    metadataColumn: String = "metadata",
):
  def validated: Either[String, PgVectorConfig] =
    val identifiers = List(table, idColumn, textColumn, embeddingColumn, metadataColumn)
    identifiers
      .find(identifier => !PgVectorConfig.isSafeQualifiedIdentifier(identifier))
      .fold[Either[String, PgVectorConfig]](Right(this))(bad => Left(s"Unsafe SQL identifier: $bad"))

object PgVectorConfig:
  private val Identifier = "^[A-Za-z_][A-Za-z0-9_]*$".r

  def isSafeQualifiedIdentifier(value: String): Boolean =
    value.split("\\.").toList match
      case Nil   => false
      case parts => parts.forall(part => Identifier.matches(part))

final class PgVectorEmbeddingStore[F[_]: Sync](
    dataSource: DataSource,
    config: PgVectorConfig,
) extends EmbeddingStore[F]:

  private val safeConfig =
    config.validated.fold(error => throw IllegalArgumentException(error), identity)

  override def add(records: List[EmbeddingRecord]): F[Unit] =
    Sync[F].blocking {
      withConnection { connection =>
        val sql =
          s"""insert into ${safeConfig.table}
             |(${safeConfig.idColumn}, ${safeConfig.textColumn}, ${safeConfig.embeddingColumn}, ${safeConfig.metadataColumn})
             |values (?, ?, ?::vector, ?::jsonb)
             |on conflict (${safeConfig.idColumn}) do update set
             |${safeConfig.textColumn} = excluded.${safeConfig.textColumn},
             |${safeConfig.embeddingColumn} = excluded.${safeConfig.embeddingColumn},
             |${safeConfig.metadataColumn} = excluded.${safeConfig.metadataColumn}
             |""".stripMargin

        val statement = connection.prepareStatement(sql)
        try
          records.foreach { record =>
            statement.setString(1, record.id)
            statement.setString(2, record.text)
            statement.setString(3, record.embedding.toPgVectorLiteral)
            statement.setString(4, write(record.metadata))
            statement.addBatch()
          }
          statement.executeBatch()
          ()
        finally statement.close()
      }
    }

  override def search(
      query: EmbeddingVector,
      maxResults: Int,
      minScore: Option[Double] = None,
  ): F[List[RetrievedSource]] =
    Sync[F].blocking {
      withConnection { connection =>
        val sql =
          s"""select ${safeConfig.idColumn}, ${safeConfig.textColumn}, ${safeConfig.metadataColumn}::text,
             |1 - (${safeConfig.embeddingColumn} <=> ?::vector) as score
             |from ${safeConfig.table}
             |order by ${safeConfig.embeddingColumn} <=> ?::vector
             |limit ?
             |""".stripMargin

        val statement = connection.prepareStatement(sql)
        try
          val vector = query.toPgVectorLiteral
          statement.setString(1, vector)
          statement.setString(2, vector)
          statement.setInt(3, maxResults.max(0))
          val resultSet = statement.executeQuery()
          try
            resultSet.toRetrievedSources
              .filter(source => minScore.forall(source.score >= _))
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
             |${safeConfig.idColumn} text primary key,
             |${safeConfig.textColumn} text not null,
             |${safeConfig.embeddingColumn} vector not null,
             |${safeConfig.metadataColumn} jsonb not null default '{}'::jsonb
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
          score = resultSet.getDouble(4),
        )
      builder.result()

  private def parseMetadata(raw: String | Null): Map[String, String] =
    Option(raw).flatMap { value =>
      Either.catchNonFatal(read[Map[String, String]](value)).toOption
    }.getOrElse(Map.empty)

