package org.l4j.template.llm4s.dsl

object RunnableGraph:
  sealed trait Node

  object Node:
    final case class Leaf(label: String) extends Node
    final case class Unary(label: String, child: Node) extends Node
    final case class Binary(label: String, left: Node, right: Node) extends Node

  def toMermaid(node: Node): String =
    val lines = scala.collection.mutable.ArrayBuffer("graph TD")
    var nextId = 0

    def freshId(): String =
      val id = s"n$nextId"
      nextId += 1
      id

    def escape(label: String): String =
      label.replace("\\", "\\\\").replace("\"", "\\\"")

    def render(current: Node): String =
      current match
        case Node.Leaf(label) =>
          val id = freshId()
          lines += s"""  $id["${escape(label)}"]"""
          id
        case Node.Unary(label, child) =>
          val id = freshId()
          lines += s"""  $id["${escape(label)}"]"""
          val childId = render(child)
          lines += s"  $id --> $childId"
          id
        case Node.Binary(label, left, right) =>
          val id = freshId()
          lines += s"""  $id["${escape(label)}"]"""
          val leftId = render(left)
          val rightId = render(right)
          lines += s"  $id --> $leftId"
          lines += s"  $id --> $rightId"
          id

    val _ = render(node)
    lines.mkString("\n")
