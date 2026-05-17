package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import sttp.capabilities.Effect
import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.SttpBackend
import sttp.monad.MonadError as SttpMonadError

class OpenAiCompatBackendResourceSpec extends FunSuite:

  test("OpenAiCompatBackend.fromSttp wires the transport against a supplied backend") {
    val sttpBackend = stubSttpBackend(
      """{
        |  "id":"r1",
        |  "choices":[{"finish_reason":"stop","message":{"content":"Hi"}}]
        |}""".stripMargin
    )

    val backend = OpenAiCompatBackend.fromSttp[IO](
      OpenAiCompatConfig("https://example.test/v1", "k", "m"),
      sttpBackend,
    )

    val text = backend.chat(ChatRequest(List(ChatMessage.UserMessage.from("?")))).unsafeRunSync().text
    assertEquals(text, "Hi")
  }

  test("Resource lifetime is observed: finalizer runs on .use exit") {
    val program = for
      closed <- Ref.of[IO, Int](0)
      sttpResource = Resource.make(
        IO.pure(stubSttpBackend("""{"id":"r","choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
      )(_ => closed.update(_ + 1))
      backendResource = sttpResource.map { sttp =>
        OpenAiCompatBackend.fromSttp[IO](
          OpenAiCompatConfig("https://example.test/v1", "k", "m"),
          sttp,
        )
      }
      _ <- backendResource.use { backend =>
        backend.chat(ChatRequest(List(ChatMessage.UserMessage.from("?")))).void
      }
      finalCount <- closed.get
    yield finalCount

    assertEquals(program.unsafeRunSync(), 1)
  }

  test("OpenAiCompatStreamingBackend.resource releases its inner transport") {
    val program = for
      released <- Ref.of[IO, Int](0)
      transportResource = Resource.make(IO.pure(stubStreamingTransport))(_ => released.update(_ + 1))
      streamingResource = OpenAiCompatStreamingBackend.resource[IO](
        OpenAiCompatConfig("https://example.test/v1", "k", "m"),
        transportResource,
      )
      _ <- streamingResource.use { backend =>
        backend.stream(ChatRequest(List(ChatMessage.UserMessage.from("?")))).compile.drain
      }
      finalCount <- released.get
    yield finalCount

    assertEquals(program.unsafeRunSync(), 1)
  }

  private def stubSttpBackend(body: String): SttpBackend[IO, Any] =
    new SttpBackend[IO, Any]:
      override def send[T, R >: Any & Effect[IO]](request: Request[T, R]): IO[Response[T]] =
        val raw = body.asInstanceOf[T]
        IO.pure(Response.ok(raw))

      override def close(): IO[Unit] = IO.unit
      override val responseMonad: SttpMonadError[IO] = new sttp.client3.impl.cats.CatsMonadAsyncError[IO]

  private def stubStreamingTransport: OpenAiStreamingTransport[IO] =
    new OpenAiStreamingTransport[IO]:
      override def stream(
          path: String,
          body: ujson.Value,
          headers: Map[String, String],
      ): Stream[IO, String] =
        Stream.empty
