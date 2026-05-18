package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite

class RunnableSpec extends FunSuite:

  test("runnable composition keeps dataflow explicit and typed") {
    val chain =
      Runnable
        .fromFunction[IO, Int, String](value => s"n=$value")
        .map(_.length)
        .contramap[Int](_ + 1)

    val result = chain.run(9, RunnableSpecSupport.stubContext).unsafeRunSync()

    assertEquals(result, 4)
  }

  test("named wrapper preserves runnable behavior") {
    val named = Runnable.fromFunction[IO, Int, Int](_ + 2).named("increment")

    assertEquals(named.label, "increment")
    assertEquals(named.run(3, RunnableSpecSupport.stubContext).unsafeRunSync(), 5)
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
      attempt <- left.zipPar(right).run((), RunnableSpecSupport.stubContext).attempt
      wasCanceled <- canceled.get
    yield (attempt, wasCanceled)

    val (attempt, wasCanceled) = program.unsafeRunSync()

    assert(attempt.isLeft)
    assertEquals(attempt.swap.toOption.map(_.getMessage), Some("boom"))
    assert(wasCanceled)
  }
