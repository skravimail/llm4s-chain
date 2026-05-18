package org.l4j.template.demo

import cats.effect.IO
import cats.effect.IOApp
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.ResponseFormat
import org.l4j.template.llm4s.dsl.*
import org.l4j.template.llm4s.rag.ContentRetriever
import org.l4j.template.llm4s.rag.RetrievedSource
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.SchemaEncoder
import org.l4j.template.llm4s.tools.ValueDecoder

/** Operator-focused LCEL demo.
  *
  * Run with:
  *
  *   sbt "runMain org.l4j.template.demo.LCEL_OPERATOR_DEMO"
  */
final case class OperatorSummary(title: String, bullets: List[String])
    derives StructuredCodec,
      SchemaEncoder,
      ValueDecoder

object LCEL_OPERATOR_DEMO extends IOApp.Simple:

  private val backend = new ChatBackend[IO]:
    override def chat(request: ChatRequest): IO[ChatResponse] =
      val userText = request.messages.collect { case message: ChatMessage.UserMessage => message.text }.lastOption.getOrElse("")

      val text =
        request.responseFormat match
          case Some(_) =>
            """{"title":"Operator DSL","bullets":["pipe with |","chain with >>","typed output"]}"""
          case None if userText.contains("retrieved context") =>
            s"Operator RAG answer:\n$userText"
          case None =>
            s"Operator plain answer: $userText"

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
          RetrievedSource("lcel", s"LCEL-style context for: $query", score = 0.94),
          RetrievedSource("scala", "Scala operators can keep chains concise", score = 0.73),
        )
      )

  private val plainChain =
    PromptTemplate.user[IO, String](system = Some("Answer concisely."))(topic => s"Explain $topic") >>
      AiAgentRunnable.fromContext[IO]()

  private val typedChain =
    PromptTemplate.user[IO, String](
      system = Some("Return JSON only."),
      responseFormat = Some(
        ResponseFormat.JsonSchema(
          name = "OperatorSummary",
          schema = summon[StructuredCodec[OperatorSummary]].schema,
          strict = true,
        )
      ),
    )(_ => "Summarize operator chaining") |
      ChatModel[IO] |
      TextOutput[IO] |
      StructuredParser[IO, OperatorSummary]

  private val ragChain =
    Input[IO, String]
      .par(ContentRetrieverRunnable[IO](retriever)) >>
      PromptTemplate.retrievalAugmentedUser[IO, (String, List[RetrievedSource])](
        system = Some("Use retrieved context when it helps.")
      )(_._1, _._2) >>
      AiAgentRunnable.fromContext[IO]()

  override def run: IO[Unit] =
    for
      _ <- IO.println("\n[1/3] >> plain chain")
      plain <- plainChain.run("opaque types", ctx)
      _ <- IO.println(plain)

      _ <- IO.println("\n[2/3] | typed chain")
      summary <- typedChain.run("ignored", ctx)
      _ <- IO.println(s"title: ${summary.title}")
      _ <- IO.println(s"bullets: ${summary.bullets.mkString(", ")}")

      _ <- IO.println("\n[3/3] >> retrieval chain")
      rag <- ragChain.run("how should an LCEL pipe read in Scala?", ctx)
      _ <- IO.println(rag)
    yield ()
