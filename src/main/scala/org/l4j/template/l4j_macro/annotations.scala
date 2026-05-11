package org.l4j.template.l4j_macro

import scala.annotation.StaticAnnotation

/**
 * Method annotation: system prompt for an AiService method.
 *
 * Read at macro-expansion time by `AiService.materialize`; never used reflectively.
 */
final class system(val value: String) extends StaticAnnotation

/**
 * Method annotation: user message template for an AiService method.
 *
 * `{{paramName}}` placeholders are validated against the method's parameter list at
 * compile time, then substituted at call time.
 */
final class user(val value: String) extends StaticAnnotation

/**
 * Method annotation on a tool object: marks a method as callable by the LLM.
 *
 * The `value` is the human-readable description handed to the model so it can
 * decide when to call the tool.
 */
final class tool(val value: String) extends StaticAnnotation

/** Parameter annotation: human-readable description for a `@tool` method parameter. */
final class param(val value: String) extends StaticAnnotation
