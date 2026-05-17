package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import fs2.Stream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.streaming.StreamEvent
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

  test("PR-19: OpenAiCompatConfig.requestTimeout has a sensible default and propagates to the resource builder") {
    import scala.concurrent.duration.*
    // Default-construct: should be the documented 60-second sttp default.
    val default = OpenAiCompatConfig("http://x/v1", "k", "m")
    assertEquals(default.requestTimeout, 60.seconds)

    // Overriding through copy is the normal customisation path.
    val tuned = default.copy(requestTimeout = 300.seconds)
    assertEquals(tuned.requestTimeout, 300.seconds)

    // Smoke-test that the Resource builds with a custom timeout (we don't
    // hit the network — just prove the wiring compiles and doesn't throw).
    val res = OpenAiCompatBackend.resource[IO](tuned)
    res.use(_ => IO.unit).unsafeRunSync()
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

  test("OpenAiCompatStreamingBackend.resource streams real SSE lines over HTTP") {
    val program =
      localHttpServer { exchange =>
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.getResponseBody
        try
          writeUtf8(out, """data: {"choices":[{"delta":{"content":"Hel""")
          writeUtf8(out, """lo"},"finish_reason":null}]}""")
          writeUtf8(out, "\n\n")
          writeUtf8(out, """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""")
          writeUtf8(out, "\n\n")
          writeUtf8(out, "data: [DONE]\n\n")
        finally out.close()
      }.use { baseUrl =>
        OpenAiCompatStreamingBackend.resource[IO](
          OpenAiCompatConfig(baseUrl, "k", "m")
        ).use { backend =>
          backend
            .stream(ChatRequest(List(ChatMessage.UserMessage.from("?"))))
            .compile
            .toList
        }
      }

    val events = program.unsafeRunSync()
    assertEquals(events.collect { case StreamEvent.TextDelta(value) => value }, List("Hello"))
    assertEquals(events.collect { case StreamEvent.Completed(r) => r.finishReason }, List(Some(FinishReason.Stop)))
  }

  test("OpenAiCompatStreamingBackend.resource surfaces non-success HTTP statuses") {
    val program =
      localHttpServer { exchange =>
        exchange.sendResponseHeaders(401, 12)
        val out = exchange.getResponseBody
        try writeUtf8(out, "unauthorized")
        finally out.close()
      }.use { baseUrl =>
        OpenAiCompatStreamingBackend.resource[IO](
          OpenAiCompatConfig(baseUrl, "k", "m")
        ).use { backend =>
          backend.stream(ChatRequest(List(ChatMessage.UserMessage.from("?")))).compile.drain.attempt
        }
      }

    val result = program.unsafeRunSync()
    assert(result.left.exists {
      case OpenAiHttpError.Unauthorized(body) => body == "unauthorized"
      case _                                  => false
    })
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

  private def localHttpServer(
      handle: com.sun.net.httpserver.HttpExchange => Unit
  ): Resource[IO, String] =
    Resource.make {
      IO.blocking {
        val server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions", exchange => handle(exchange))
        server.start()
        server
      }
    }(server => IO.blocking(server.stop(0))).map { server =>
      s"http://127.0.0.1:${server.getAddress.getPort}/v1"
    }

  private def writeUtf8(out: java.io.OutputStream, text: String): Unit =
    out.write(text.getBytes(StandardCharsets.UTF_8))
    out.flush()
