package org.l4j.template.llm4s.runtime

import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema

trait ToolExecutor[F[_]]:
  def execute(call: ToolCall, context: InvocationContext): F[ToolResult]

/** A schema and its executor, paired and identified by `schema.name`. */
final case class ToolEntry[F[_]](schema: ToolSchema, executor: ToolExecutor[F]):
  def name: String = schema.name

/** A bundle of tools indexed by name. The single-Map representation makes it
  * impossible to register a schema without a matching executor (or vice
  * versa), which the previous two-collection shape did not enforce.
  *
  * The legacy `schemas` / `executors` views are kept so existing callers
  * (`AiRuntime`, wire encoders) work unchanged.
  */
final case class ToolKit[F[_]] private (entries: Map[String, ToolEntry[F]]):
  /** Schemas in registration order — preserved by `LinkedHashMap` semantics. */
  def schemas: List[ToolSchema] = entries.values.iterator.map(_.schema).toList

  /** Executors keyed by tool name. */
  def executors: Map[String, ToolExecutor[F]] =
    entries.view.mapValues(_.executor).toMap

  def names: Set[String] = entries.keySet
  def size: Int = entries.size
  def isEmpty: Boolean = entries.isEmpty
  def contains(name: String): Boolean = entries.contains(name)
  def get(name: String): Option[ToolEntry[F]] = entries.get(name)

  /** Combine two ToolKits; entries on the right take precedence on name clash. */
  def ++(other: ToolKit[F]): ToolKit[F] = new ToolKit[F](entries ++ other.entries)

  /** Add or replace a single entry. */
  def withEntry(entry: ToolEntry[F]): ToolKit[F] =
    new ToolKit[F](entries.updated(entry.name, entry))

object ToolKit:
  def empty[F[_]]: ToolKit[F] = new ToolKit[F](Map.empty)

  /** Build a toolkit from explicit entries. Later entries with the same name
    * replace earlier ones. */
  def of[F[_]](entries: ToolEntry[F]*): ToolKit[F] =
    new ToolKit[F](entries.iterator.map(e => e.name -> e).toMap)

  /** Build a toolkit from (schema, executor) pairs. */
  def fromPairs[F[_]](pairs: (ToolSchema, ToolExecutor[F])*): ToolKit[F] =
    of(pairs.map { case (s, x) => ToolEntry(s, x) }*)

  /** Backwards-compatible constructor mirroring the pre-refactor shape.
    *
    * Pairs schemas with executors by name. Throws `IllegalArgumentException`
    * if any schema has no executor (or vice versa) or if a schema name is
    * duplicated. The old representation silently allowed those — that is the
    * specific footgun this refactor closes — so we surface it loudly here.
    */
  def apply[F[_]](
      schemas: List[ToolSchema],
      executors: Map[String, ToolExecutor[F]],
  ): ToolKit[F] =
    val schemaNames = schemas.map(_.name)
    val duplicates = schemaNames.groupBy(identity).collect { case (n, xs) if xs.size > 1 => n }.toList
    require(duplicates.isEmpty, s"ToolKit: duplicate schema names: ${duplicates.mkString(", ")}")
    val schemaNameSet = schemaNames.toSet
    val executorNames = executors.keySet
    val orphanSchemas = schemaNameSet -- executorNames
    val orphanExecutors = executorNames -- schemaNameSet
    require(
      orphanSchemas.isEmpty,
      s"ToolKit: schemas without executors: ${orphanSchemas.toList.sorted.mkString(", ")}",
    )
    require(
      orphanExecutors.isEmpty,
      s"ToolKit: executors without schemas: ${orphanExecutors.toList.sorted.mkString(", ")}",
    )
    val entryMap = schemas.iterator.map { s =>
      s.name -> ToolEntry(s, executors(s.name))
    }.toMap
    new ToolKit[F](entryMap)
