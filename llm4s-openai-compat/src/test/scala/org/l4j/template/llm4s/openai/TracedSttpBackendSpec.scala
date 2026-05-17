package org.l4j.template.llm4s.openai

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import sttp.capabilities.Effect
import sttp.client3.Request
import sttp.client3.Response
import sttp.client3.SttpBackend
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
      override def onHttpRequest(m: Method, u: Uri): IO[Unit] =
        buf.update(_ :+ Event.Req(m.method, u.toString))
      override def onHttpResponse(m: Method, u: Uri, s: Int, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Resp(s, d >= 0))
      override def onHttpFailure(m: Method, u: Uri, e: Throwable, d: Long): IO[Unit] =
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
