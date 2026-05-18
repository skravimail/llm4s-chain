package org.l4j.template.llm4s.dsl

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scala.concurrent.duration.*

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
      _ <- IO.sleep(50.millis)
      wasCanceled <- canceled.get
    yield (attempt, wasCanceled)

    val (attempt, wasCanceled) = program.unsafeRunSync()

    assert(attempt.isLeft)
    assertEquals(attempt.swap.toOption.map(_.getMessage), Some("boom"))
    assert(wasCanceled)
  }

  test("mermaid rendering includes composition structure and labels") {
    val chain =
      PromptTemplate
        .user[IO, String](system = Some("system"))(identity)
        .andThen(ChatModel[IO])
        .andThen(TextOutput[IO])
        .named("answer-chain")

    val mermaid = chain.toMermaid

    assert(mermaid.startsWith("graph TD"))
    assert(mermaid.contains("answer-chain"))
    assert(mermaid.contains("andThen"))
    assert(mermaid.contains("prompt-template"))
    assert(mermaid.contains("chat-model"))
    assert(mermaid.contains("text-output"))
  }

  test("graph description preserves parallel branch labels") {
    val left = Runnable.fromFunction[IO, Int, Int](_ + 1).named("left")
    val right = Runnable.fromFunction[IO, Int, String](_.toString).named("right")

    val graph = left.zipPar(right).describeGraph

    assertEquals(
      graph,
      RunnableGraph.Node.Binary(
        "zipPar",
        RunnableGraph.Node.Unary("left", RunnableGraph.Node.Leaf("function")),
        RunnableGraph.Node.Unary("right", RunnableGraph.Node.Leaf("function")),
      )
    )
  }
