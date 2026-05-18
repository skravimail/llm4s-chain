package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.tools.SchemaEncoder.given
import org.l4j.template.llm4s.tools.ValueDecoder.given

class ChatModelSpec extends FunSuite:

  test("prompt template model node and text output build a simple chain") {
    val chain =
      PromptTemplate.user[IO, String](system = Some("Be concise."))(topic => s"Explain $topic") andThen
        ChatModel[IO] andThen
        TextOutput[IO]

    val result = chain.run("opaque types", textContext.unsafeRunSync()).unsafeRunSync()

    assertEquals(result, "answer: Explain opaque types")
  }

  test("structured parsing is a separate terminal node") {
    final case class Summary(title: String, bullets: List[String])
    given StructuredCodec[Summary] = StructuredCodec.derived[Summary]

    val chain =
      PromptTemplate.user[IO, String](
        system = Some("Return JSON only.")
      )(topic => s"Summarize $topic") andThen
        ChatModel[IO] andThen
        TextOutput[IO] andThen
        StructuredParser[IO, Summary]

    val result = chain.run("Scala 3", jsonContext.unsafeRunSync()).unsafeRunSync()

    assertEquals(result.title, "Scala 3")
    assertEquals(result.bullets, List("opaque types", "givens"))
  }

  test("chat model uses the context trace and fires provider listener events") {
    val program = for
      requests <- Ref.of[IO, List[(TraceContext, Int, String)]](Nil)
      responses <- Ref.of[IO, List[(TraceContext, Int, String)]](Nil)
      trace = TraceContext.fresh()
      backend = new ChatBackend[IO]:
        override def chat(request: ChatRequest): IO[ChatResponse] =
          IO.pure(ChatResponse(ChatMessage.AiMessage.from(request.messages.last.text.reverse)))
        override def chat(request: ChatRequest, actualTrace: TraceContext): IO[ChatResponse] =
          IO.pure(ChatResponse(ChatMessage.AiMessage.from(s"${actualTrace.traceId}:${request.messages.last.text}")))
      listener = new RuntimeListener.Default[IO]:
        override def onProviderRequest(actualTrace: TraceContext, turn: Int, request: ChatRequest): IO[Unit] =
          requests.update((actualTrace, turn, request.messages.last.text) :: _)

        override def onProviderResponse(
            actualTrace: TraceContext,
            turn: Int,
            response: ChatResponse,
            durationNanos: Long,
        ): IO[Unit] =
          responses.update((actualTrace, turn, response.text) :: _)
      context = RunContext[IO](
        backend0 = backend,
        runtimeConfig0 = RuntimeConfig(),
        runtimeListener0 = listener,
        traceContext0 = trace,
      )
      response <- ChatModel[IO].run(
        ChatRequest(messages = List(ChatMessage.UserMessage.from("hello"))),
        context,
      )
      seenRequests <- requests.get
      seenResponses <- responses.get
    yield (trace, response, seenRequests.reverse, seenResponses.reverse)

    val (trace, response, seenRequests, seenResponses) = program.unsafeRunSync()

    assert(response.text.startsWith(s"${trace.traceId}:"))
    assertEquals(seenRequests, List((trace, 0, "hello")))
    assertEquals(seenResponses.map { case (actualTrace, turn, text) => (actualTrace, turn, text.startsWith(s"${trace.traceId}:")) }, List((trace, 0, true)))
  }

  private def textContext: IO[RunContext[IO]] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.pure(ChatResponse(ChatMessage.AiMessage.from(s"answer: ${request.messages.last.text}")))

    IO.pure(
      RunContext[IO](
        backend0 = backend,
        runtimeConfig0 = RuntimeConfig(),
        runtimeListener0 = RuntimeListener.noop[IO],
      )
    )

  private def jsonContext: IO[RunContext[IO]] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.pure(
          ChatResponse(
            ChatMessage.AiMessage.from(
              """{"title":"Scala 3","bullets":["opaque types","givens"]}"""
            )
          )
        )

    IO.pure(
      RunContext[IO](
        backend0 = backend,
        runtimeConfig0 = RuntimeConfig(),
        runtimeListener0 = RuntimeListener.noop[IO],
      )
    )
