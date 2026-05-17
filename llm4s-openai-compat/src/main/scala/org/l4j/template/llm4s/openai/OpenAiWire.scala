package org.l4j.template.llm4s.openai

import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolSchema
import org.l4j.template.llm4s.core.Usage

object OpenAiWire:

  def encodeChatRequest(
      model: String,
      request: ChatRequest,
      responseFormatMode: ResponseFormatMode = ResponseFormatMode.JsonSchema,
  ): ujson.Obj =
    val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
      "model" -> ujson.Str(model),
      "messages" -> ujson.Arr.from(request.messages.map(encodeMessage)),
    )

    if request.tools.nonEmpty then
      fields += "tools" -> ujson.Arr.from(request.tools.map(encodeTool))

    request.responseFormat.foreach {
      case ResponseFormat.Text => ()
      case json: ResponseFormat.JsonSchema =>
        responseFormatMode match
          case ResponseFormatMode.JsonSchema =>
            fields += "response_format" -> ujson.Obj(
              "type" -> "json_schema",
              "json_schema" -> ujson.Obj(
                "name" -> json.name,
                "strict" -> json.strict,
                "schema" -> encodeSchema(json.schema),
              ),
            )
          case ResponseFormatMode.JsonObject =>
            // Older servers (osaurus, older LM Studio, llama.cpp) only
            // support the un-schema'd json_object mode. The library's own
            // ValueDecoder still validates the response shape after parse.
            fields += "response_format" -> ujson.Obj("type" -> "json_object")
          case ResponseFormatMode.Disabled =>
            // Send no response_format field — rely on the prompt to coax
            // JSON-shaped output. Combine with low temperature for stability.
            ()
    }

    request.temperature.foreach { value =>
      fields += "temperature" -> ujson.Num(value)
    }

    if request.metadata.nonEmpty then
      fields += "metadata" -> ujson.Obj.from(request.metadata.view.mapValues(ujson.Str(_)).toMap)

    ujson.Obj.from(fields)

  def decodeChatResponse(json: ujson.Value): ChatResponse =
    val choices = json.obj.get("choices").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
    val choice = choices.headOption.getOrElse(
      throw RuntimeException("OpenAI-compatible response did not include any choices")
    )
    val messageJson = choice.obj.get("message").getOrElse(ujson.Obj())
    val toolCalls = messageJson.obj.get("tool_calls")
      .flatMap(_.arrOpt)
      .map(_.toList.flatMap(decodeToolCall))
      .getOrElse(Nil)

    val finishReason = choice.obj.get("finish_reason").flatMap(decodeFinishReason)

    val message = ChatMessage.AiMessage(
      contents = decodeContents(messageJson.obj.get("content")),
      toolCalls = toolCalls,
      finishReason = finishReason,
    )

    ChatResponse(
      message = message,
      usage = decodeUsage(json.obj.get("usage")),
      finishReason = finishReason,
      responseId = json.obj.get("id").collect { case ujson.Str(value) => value },
    )

  private def encodeMessage(message: ChatMessage): ujson.Obj =
    message match
      case ChatMessage.ToolResultMessage(_, toolCallId, result) =>
        ujson.Obj(
          "role" -> "tool",
          "content" -> ujson.Str(result.text),
          "tool_call_id" -> toolCallId.getOrElse(""),
        )
      case other =>
        ujson.Obj(
          "role" -> other.role,
          "content" -> encodeContents(other.contents),
        )

  private def encodeContents(contents: List[AiContent]): ujson.Value =
    if contents.forall(_.isInstanceOf[AiContent.Text]) then
      ujson.Str(contents.flatMap(_.textValue).mkString)
    else
      ujson.Arr.from(contents.map {
        case AiContent.Text(value) =>
          ujson.Obj("type" -> "text", "text" -> value)
        case AiContent.Image(base64Data, mimeType, detail) =>
          val imageUrl = ujson.Obj(
            "url" -> s"data:$mimeType;base64,$base64Data"
          )
          detail.foreach(imageUrl("detail") = _)
          ujson.Obj(
            "type" -> "image_url",
            "image_url" -> imageUrl,
          )
        case AiContent.File(base64Data, mimeType, fileName) =>
          val file = ujson.Obj(
            "file_data" -> s"data:$mimeType;base64,$base64Data"
          )
          fileName.foreach(file("filename") = _)
          ujson.Obj(
            "type" -> "file",
            "file" -> file,
          )
      })

  private def decodeContents(content: Option[ujson.Value]): List[AiContent] =
    content match
      case Some(ujson.Str(value)) => List(AiContent.Text(value))
      case Some(ujson.Arr(values)) =>
        values.toList.flatMap {
          case ujson.Obj(fields) if fields.get("type").contains(ujson.Str("text")) =>
            fields.get("text").collect { case ujson.Str(value) => AiContent.Text(value) }
          case _ => Nil
        }
      case _ => Nil

  private def encodeTool(tool: ToolSchema): ujson.Obj =
    ujson.Obj(
      "type" -> "function",
      "function" -> ujson.Obj(
        "name" -> tool.name,
        "description" -> tool.description,
        "parameters" -> encodeSchema(tool.parameters),
      ),
    )

  private def encodeSchema(schema: JsonSchema): ujson.Value =
    schema match
      case JsonSchema.ObjectSchema(properties, required, description, definitions) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "type" -> ujson.Str("object"),
          "properties" -> ujson.Obj.from(properties.view.mapValues(encodeSchema).toMap),
        )
        if required.nonEmpty then fields += "required" -> ujson.Arr.from(required.toList.sorted.map(ujson.Str(_)))
        description.foreach(value => fields += "description" -> ujson.Str(value))
        if definitions.nonEmpty then
          fields += "definitions" -> ujson.Obj.from(definitions.view.mapValues(encodeSchema).toMap)
        ujson.Obj.from(fields)
      case JsonSchema.StringSchema(description, enumValues) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "type" -> ujson.Str("string")
        )
        description.foreach(value => fields += "description" -> ujson.Str(value))
        if enumValues.nonEmpty then fields += "enum" -> ujson.Arr.from(enumValues.map(ujson.Str(_)))
        ujson.Obj.from(fields)
      case JsonSchema.IntegerSchema(description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value]("type" -> ujson.Str("integer"))
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.NumberSchema(description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value]("type" -> ujson.Str("number"))
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.BooleanSchema(description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value]("type" -> ujson.Str("boolean"))
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.ArraySchema(items, description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "type" -> ujson.Str("array"),
          "items" -> encodeSchema(items),
        )
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.EnumSchema(values, description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "type" -> ujson.Str("string"),
          "enum" -> ujson.Arr.from(values.map(ujson.Str(_))),
        )
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.RefSchema(reference, description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "$ref" -> ujson.Str(s"#/definitions/$reference")
        )
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)
      case JsonSchema.AnyOfSchema(alternatives, description) =>
        val fields = collection.mutable.LinkedHashMap[String, ujson.Value](
          "anyOf" -> ujson.Arr.from(alternatives.map(encodeSchema))
        )
        description.foreach(value => fields += "description" -> ujson.Str(value))
        ujson.Obj.from(fields)

  private def decodeToolCall(json: ujson.Value): Option[ToolCall] =
    for
      function <- json.obj.get("function").flatMap(_.objOpt)
      name     <- function.value.get("name").flatMap(_.strOpt)
      args     <- function.value.get("arguments").flatMap(_.strOpt)
    yield ToolCall(
      name = name,
      argumentsJson = args,
      callId = json.obj.get("id").collect { case ujson.Str(value) => value },
    )

  private def decodeUsage(json: Option[ujson.Value]): Option[Usage] =
    json.map { usage =>
      Usage(
        inputTokens = usage.obj.get("prompt_tokens").collect { case ujson.Num(value) => value.toInt }.getOrElse(0),
        outputTokens = usage.obj.get("completion_tokens").collect { case ujson.Num(value) => value.toInt }.getOrElse(0),
      )
    }

  private def decodeFinishReason(json: ujson.Value): Option[FinishReason] =
    json match
      case ujson.Str("stop")           => Some(FinishReason.Stop)
      case ujson.Str("length")         => Some(FinishReason.Length)
      case ujson.Str("tool_calls")     => Some(FinishReason.ToolCalls)
      case ujson.Str("content_filter") => Some(FinishReason.ContentFilter)
      case _                           => None
