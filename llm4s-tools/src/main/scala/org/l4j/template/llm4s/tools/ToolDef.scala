package org.l4j.template.llm4s.tools

import org.l4j.template.llm4s.core.JsonSchema

import scala.deriving.Mirror

/** A single type class that bundles a JSON schema and a decoder for the
  * argument type of a tool. With this you can write:
  *
  * {{{
  * final case class Greet(name: String) derives ToolDef
  * }}}
  *
  * and the schema + decoder are both available from the same instance —
  * removing the silent drift problem where the schema you advertise to the
  * model and the decoder you parse with can fall out of sync.
  *
  * Implemented via Scala 3 inline derivation (no macros, no `@experimental`).
  */
trait ToolDef[A]:
  def schema: JsonSchema
  def decoder: ValueDecoder[A]
  def isOptional: Boolean = false

object ToolDef:
  def apply[A](using td: ToolDef[A]): ToolDef[A] = td

  /** Default instance: anywhere both `SchemaEncoder[A]` and `ValueDecoder[A]`
    * are in scope (which holds for primitives, Option, List, and anything
    * already deriving the underlying typeclasses), a `ToolDef[A]` is summoned
    * automatically. */
  given fromComponents[A](using s: SchemaEncoder[A], d: ValueDecoder[A]): ToolDef[A] =
    OfComponents[A](s.schema, d, s.isOptional)

  /** Enables `derives ToolDef` on case classes and enums by delegating to the
    * inline `SchemaEncoder.derived` and `ValueDecoder.derived`. */
  inline given derived[A](using mirror: Mirror.Of[A]): ToolDef[A] =
    OfComponents[A](
      SchemaEncoder.derived[A](using mirror).schema,
      ValueDecoder.derived[A](using mirror),
      false,
    )

  /** Concrete implementation, exposed so that inline-derived instances can
    * instantiate it from a call site outside this object. Not intended for
    * direct construction — use `ToolDef.derived` or summon via the
    * `fromComponents` given. */
  final class OfComponents[A](
      override val schema: JsonSchema,
      override val decoder: ValueDecoder[A],
      override val isOptional: Boolean,
  ) extends ToolDef[A]
