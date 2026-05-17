package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sttp.capabilities.Effect
import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.SttpBackend
import org.l4j.template.llm4s.core.TraceContext
import sttp.client3.basicRequest
import sttp.model.Method
import sttp.model.StatusCode
import sttp.model.Uri
import sttp.monad.MonadError as SttpMonadError

class TracedSttpBackendSpec extends FunSuite:

  sealed trait Event
  object Event:
    final case class Req(method: String, uri: String) extends Event
    final case class Resp(status: Int, durationPositive: Boolean) extends Event
    final case class Fail(msg: String, durationPositive: Boolean) extends Event

  private def listener(buf: Ref[IO, Vector[Event]]): HttpListener[IO] =
    new HttpListener.Default[IO]:
      override def onHttpRequest(t: TraceContext, m: Method, u: Uri): IO[Unit] =
        buf.update(_ :+ Event.Req(m.method, u.toString))
      override def onHttpResponse(t: TraceContext, m: Method, u: Uri, s: Int, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Resp(s, d >= 0))
      override def onHttpFailure(t: TraceContext, m: Method, u: Uri, e: Throwable, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Fail(Option(e.getMessage).getOrElse(""), d >= 0))

  test("traced backend fires request then response on success") {
    val ok = okBackend("hello")
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      traced = TracedSttpBackend[IO, Any](ok, listener(buf))
      _ <- traced.send(basicRequest.get(Uri.unsafeParse("http://example/test")).response(sttp.client3.asStringAlways))
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events.size, 2)
    val Event.Req(method, uri) = events.head: @unchecked
    assertEquals(method, "GET")
    assert(uri.contains("example/test"))
    val Event.Resp(status, durationPositive) = events.last: @unchecked
    assertEquals(status, 200)
    assert(durationPositive)
  }

  test("traced backend fires onHttpFailure when send raises and re-raises") {
    val explode = failBackend(new RuntimeException("connect refused"))
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      traced = TracedSttpBackend[IO, Any](explode, listener(buf))
      err <- traced
        .send(basicRequest.get(Uri.unsafeParse("http://example/x")).response(sttp.client3.asStringAlways))
        .attempt
      events <- buf.get
    yield (err, events)

    val (err, events) = program.unsafeRunSync()
    assert(err.isLeft)
    assertEquals(events.size, 2)
    assert(events.head.isInstanceOf[Event.Req])
    val Event.Fail(msg, _) = events.last: @unchecked
    assertEquals(msg, "connect refused")
  }

  test("PR-8f: SttpOpenAiTransport HTTP events share the chat's TraceContext") {
    import org.l4j.template.llm4s.core.ChatMessage
    import org.l4j.template.llm4s.core.ChatRequest
    import org.l4j.template.llm4s.runtime.AiRuntime
    import org.l4j.template.llm4s.runtime.RuntimeConfig
    import org.l4j.template.llm4s.runtime.RuntimeListener
    import org.l4j.template.llm4s.runtime.ToolKit
    val responseJson =
      """{"id":"r","choices":[{"finish_reason":"stop","message":{"content":"hi"}}]}"""

    val sttpBackend: SttpBackend[IO, Any] = new SttpBackend[IO, Any]:
      override def send[T, R >: Any & Effect[IO]](r: Request[T, R]): IO[Response[T]] =
        IO.pure(Response[T](responseJson.asInstanceOf[T], StatusCode.Ok))
      override def close(): IO[Unit] = IO.unit
      override val responseMonad: SttpMonadError[IO] =
        new sttp.client3.impl.cats.CatsMonadAsyncError[IO]

    val program = for
      httpBuf <- Ref.of[IO, Vector[String]](Vector.empty)
      chatBuf <- Ref.of[IO, Vector[String]](Vector.empty)
      httpListener = new HttpListener.Default[IO]:
        override def onHttpRequest(t: TraceContext, m: Method, u: Uri): IO[Unit] =
          httpBuf.update(_ :+ t.traceId.value)
        override def onHttpResponse(t: TraceContext, m: Method, u: Uri, s: Int, d: Long): IO[Unit] =
          httpBuf.update(_ :+ t.traceId.value)
      runtimeListener = new RuntimeListener.Default[IO]:
        override def onChatStarted(t: TraceContext, r: ChatRequest): IO[Unit] =
          chatBuf.update(_ :+ t.traceId.value)
      transport = SttpOpenAiTransport[IO](
        Uri.unsafeParse("https://example.test/v1"),
        sttpBackend,
        httpListener,
      )
      backend = new OpenAiCompatBackend[IO](
        OpenAiCompatConfig("https://example.test/v1", "k", "m"),
        transport,
      )
      runtime = AiRuntime[IO](backend, RuntimeConfig(), runtimeListener)
      _ <- runtime.chat(None, "hello", ToolKit.empty[IO])
      httpIds <- httpBuf.get
      chatIds <- chatBuf.get
    yield (chatIds, httpIds)

    val (chatIds, httpIds) = program.unsafeRunSync()
    assertEquals(chatIds.size, 1, s"expected one chat-started event, got $chatIds")
    assertEquals(httpIds.distinct, chatIds, s"HTTP events must share chat's traceId, got chat=$chatIds http=$httpIds")
    // Both onHttpRequest and onHttpResponse fired.
    assertEquals(httpIds.size, 2)
  }

  test("traced backend forwards close() and responseMonad to underlying") {
    val closeCount = Ref.unsafe[IO, Int](0)
    val under = closingBackend(closeCount)
    val traced = TracedSttpBackend[IO, Any](under, HttpListener.noop[IO])

    traced.close().unsafeRunSync()
    assertEquals(closeCount.get.unsafeRunSync(), 1)
    assert(traced.responseMonad eq under.responseMonad)
  }

  // -- tiny test-only sttp backends -----------------------------------------

  private def okBackend(body: String): SttpBackend[IO, Any] =
    new SttpBackend[IO, Any]:
      override def send[T, R >: Any & Effect[IO]](r: Request[T, R]): IO[Response[T]] =
        IO.pure(Response[T](body.asInstanceOf[T], StatusCode.Ok))
      override def close(): IO[Unit] = IO.unit
      override val responseMonad: SttpMonadError[IO] =
        new sttp.client3.impl.cats.CatsMonadAsyncError[IO]

  private def failBackend(err: Throwable): SttpBackend[IO, Any] =
    new SttpBackend[IO, Any]:
      override def send[T, R >: Any & Effect[IO]](r: Request[T, R]): IO[Response[T]] =
        IO.raiseError(err)
      override def close(): IO[Unit] = IO.unit
      override val responseMonad: SttpMonadError[IO] =
        new sttp.client3.impl.cats.CatsMonadAsyncError[IO]

  private def closingBackend(counter: Ref[IO, Int]): SttpBackend[IO, Any] =
    new SttpBackend[IO, Any]:
      override def send[T, R >: Any & Effect[IO]](r: Request[T, R]): IO[Response[T]] =
        IO.raiseError(new RuntimeException("never"))
      override def close(): IO[Unit] = counter.update(_ + 1)
      override val responseMonad: SttpMonadError[IO] =
        new sttp.client3.impl.cats.CatsMonadAsyncError[IO]
