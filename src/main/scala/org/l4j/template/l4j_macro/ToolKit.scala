package org.l4j.template.l4j_macro

import dev.langchain4j.agent.tool.ToolSpecification

/**
 * Bundle of tool specifications plus a dispatcher.
 *
 * The macro-generated dispatcher is keyed on tool name and takes the JSON-encoded
 * argument string the model emitted; it returns the tool's stringified result.
 *
 * Combine kits with `++` if you have multiple tool objects.
 */
final case class ToolKit(
    specs: List[ToolSpecification],
    dispatch: Map[String, String => String],
):
  def ++(other: ToolKit): ToolKit =
    ToolKit(specs ++ other.specs, dispatch ++ other.dispatch)

object ToolKit:
  val empty: ToolKit = ToolKit(Nil, Map.empty)
