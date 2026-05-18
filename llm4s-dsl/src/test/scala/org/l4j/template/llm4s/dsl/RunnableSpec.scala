package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.TraceContext
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.RuntimeListener

class RunnableSpec extends FunSuite:

  test("runnable composition keeps dataflow explicit and typed") {
    val chain =
      Runnable
        .fromFunction[IO, Int, String](value => s"n=$value")
        .map(_.length)
        .contramap[Int](_ + 1)

    val result = chain.run(9, stubContext).unsafeRunSync()

    assertEquals(result, 4)
  }

  test("named wrapper preserves runnable behavior") {
    val named = Runnable.fromFunction[IO, Int, Int](_ + 2).named("increment")

    assertEquals(named.label, "increment")
    assertEquals(named.run(3, stubContext).unsafeRunSync(), 5)
  }

  test("zipPar fails fast and cancels the sibling branch") {
    val program = for
      canceled <- Ref.of[IO, Boolean](false)
      left = Runnable.eval[IO, Unit, Int] { (_, _) =>
        IO.raiseError(RuntimeException("boom"))
      }
      right = Runnable.eval[IO, Unit, String] { (_, _) =>
        IO.never.onCancel(canceled.set(true))
      }
      attempt <- left.zipPar(right).run((), stubContext).attempt
      wasCanceled <- canceled.get
    yield (attempt, wasCanceled)

    val (attempt, wasCanceled) = program.unsafeRunSync()

    assert(attempt.isLeft)
    assertEquals(attempt.swap.toOption.map(_.getMessage), Some("boom"))
    assert(wasCanceled)
  }

  private def stubContext: RunContext[IO] =
    val backend = new ChatBackend[IO]:
      override def chat(request: ChatRequest): IO[ChatResponse] =
        IO.raiseError(RuntimeException(s"unexpected backend call: ${request.messages.length}"))

    RunContext[IO](
      backend0 = backend,
      runtimeConfig0 = RuntimeConfig(),
      runtimeListener0 = RuntimeListener.noop[IO],
      traceContext0 = TraceContext.fresh(),
    )
