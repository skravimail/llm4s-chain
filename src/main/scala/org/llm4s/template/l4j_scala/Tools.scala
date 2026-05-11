package org.llm4s.template.l4j_scala

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.{ JsonObjectSchema, JsonSchemaElement, JsonStringSchema }

import scala.jdk.CollectionConverters._

/**
 * Helpers for langchain4j tools.
 *
 * The idiomatic langchain4j path is to define a Scala class with `@Tool`-annotated methods
 * and pass an instance via `AiService.Config(tools = Seq(myTools))`. Scala methods can carry
 * the `@Tool` and `@P` annotations directly — same syntax as Java:
 *
 * {{{
 *   import dev.langchain4j.agent.tool.{ Tool, P }
 *
 *   class WeatherTools {
 *     @Tool(Array("Get the current weather for a city"))
 *     def getWeather(@P("City name") city: String): String = s"\$city: sunny, 22C"
 *   }
 * }}}
 *
 * The helpers below are for the rarer case where you want to build a `ToolSpecification`
 * programmatically (e.g., tools whose schema is determined at runtime).
 */
object Tools {

  /** A simple parameter declaration for programmatic tool specs. */
  final case class Param(
    name: String,
    description: String,
    required: Boolean = true,
    schema: JsonSchemaElement = new JsonStringSchema(),
  )

  /**
   * Build a `ToolSpecification` programmatically. Use this when you can't or don't want
   * to declare a `@Tool`-annotated method. For most cases, just annotate a method instead.
   */
  def spec(name: String, description: String, params: Seq[Param] = Seq.empty): ToolSpecification = {
    val b = ToolSpecification.builder().name(name).description(description)
    if (params.nonEmpty) {
      val sb = JsonObjectSchema.builder()
      params.foreach { p =>
        sb.addProperty(p.name, p.schema)
        if (p.required) sb.required(p.name)
      }
      b.parameters(sb.build())
    }
    b.build()
  }

  /** Re-export commonly needed json schema elements so callers don't need a deep import. */
  object Schemas {
    def string: JsonSchemaElement = new JsonStringSchema()
    def stringEnum(values: String*): JsonSchemaElement =
      dev.langchain4j.model.chat.request.json.JsonEnumSchema.builder().enumValues(values.asJava).build()
  }
}
