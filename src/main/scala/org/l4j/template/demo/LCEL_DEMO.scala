package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.dsl.*
import org.l4j.template.llm4s.dsl.AiAgentRunnable
import org.l4j.template.llm4s.dsl.ChatModel
import org.l4j.template.llm4s.dsl.ContentRetrieverRunnable
import org.l4j.template.llm4s.dsl.Input
import org.l4j.template.llm4s.dsl.PromptTemplate
import org.l4j.template.llm4s.dsl.RunContext
import org.l4j.template.llm4s.dsl.StructuredParser
import org.l4j.template.llm4s.dsl.TextOutput
import org.l4j.template.llm4s.rag.ContentRetriever
import org.l4j.template.llm4s.rag.RetrievedSource
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

/** Small runnable demo for the LCEL-style DSL.
  *
  * Run with:
  *
  *   sbt "runMain org.l4j.template.demo.LCEL_DEMO"
  */
final case class DemoSummary(title: String, bullets: List[String])
    derives StructuredCodec,
      SchemaEncoder,
      ValueDecoder

object LCEL_DEMO extends IOApp.Simple:

  private val backend = new ChatBackend[IO]:
    override def chat(request: ChatRequest): IO[ChatResponse] =
      val userText = request.messages.collect { case message: ChatMessage.UserMessage => message.text }.lastOption.getOrElse("")

      val text =
        request.responseFormat match
          case Some(_) =>
            """{"title":"Scala 3","bullets":["givens","opaque types","extension methods"]}"""
          case None if userText.contains("retrieved context") =>
            s"RAG answer using context:\n$userText"
          case None =>
            s"Plain answer: $userText"

      IO.pure(ChatResponse(ChatMessage.AiMessage.from(text)))

  private val ctx =
    RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = RuntimeListener.noop[IO],
    )

  private val retriever = new ContentRetriever[IO]:
    override def retrieve(query: String): IO[List[RetrievedSource]] =
      IO.pure(
        List(
          RetrievedSource("scala-3", s"Scala 3 facts for: $query", score = 0.93),
          RetrievedSource("ce3", "cats-effect is the runtime foundation", score = 0.71),
        )
      )

  private val plainChain =
    PromptTemplate
      .user[IO, String](system = Some("Answer in one sentence."))(topic => s"Explain $topic")
      >> AiAgentRunnable.fromContext[IO]()

  private val typedChain =
    PromptTemplate
      .user[IO, String](
        system = Some("Return JSON only."),
        responseFormat = Some(
          ResponseFormat.JsonSchema(
            name = "DemoSummary",
            schema = summon[StructuredCodec[DemoSummary]].schema,
            strict = true,
          )
        ),
      )(_ => "Summarize Scala 3") |
      ChatModel[IO] |
      TextOutput[IO] |
      StructuredParser[IO, DemoSummary]

  private val ragChain =
    Input[IO, String]
      .par(ContentRetrieverRunnable[IO](retriever))
      >> (
        PromptTemplate.retrievalAugmentedUser[IO, (String, List[RetrievedSource])](
          system = Some("Answer using the retrieved context when it helps.")
        )(_._1, _._2)
      ) >> AiAgentRunnable.fromContext[IO]()

  override def run: IO[Unit] =
    for
      _ <- IO.println("\n[1/3] prompt -> model")
      plain <- plainChain.run("opaque types", ctx)
      _ <- IO.println(plain)

      _ <- IO.println("\n[2/3] prompt -> model -> typed parser")
      summary <- typedChain.run("Scala 3", ctx)
      _ <- IO.println(s"title: ${summary.title}")
      _ <- IO.println(s"bullets: ${summary.bullets.mkString(", ")}")

      _ <- IO.println("\n[3/3] input -> retrieve -> enrich prompt -> model")
      rag <- ragChain.run("how does Scala 3 help DSL design?", ctx)
      _ <- IO.println(rag)

      _ <- IO.println("\nMermaid graph for the RAG chain:")
      _ <- IO.println(ragChain.toMermaid)
    yield ()
