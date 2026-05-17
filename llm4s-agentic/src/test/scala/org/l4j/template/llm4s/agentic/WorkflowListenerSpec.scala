package org.l4j.template.llm4s.agentic

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite

class WorkflowListenerSpec extends FunSuite:

  sealed trait Event
  object Event:
    final case class Started(name: String, input: Any) extends Event
    final case class Succeeded(name: String, output: Any) extends Event
    final case class Failed(name: String, msg: String) extends Event

  private def listener(buf: Ref[IO, Vector[Event]]): WorkflowListener[IO] =
    new WorkflowListener.Default[IO]:
      override def onAgentStarted(n: String, i: Any): IO[Unit] =
        buf.update(_ :+ Event.Started(n, i))
      override def onAgentSucceeded(n: String, o: Any, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Succeeded(n, o))
      override def onAgentFailed(n: String, e: Throwable, d: Long): IO[Unit] =
        buf.update(_ :+ Event.Failed(n, Option(e.getMessage).getOrElse("")))

  test("instrumented agent fires started + succeeded on happy path") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      base = WorkflowAgent.lift[IO, String, String]("upper")((s, _) => IO.pure(s.toUpperCase))
      wrapped = base.instrumented(listener(buf))
      scope <- AgentScope.create[IO]
      result <- wrapped.run("hi", scope)
      events <- buf.get
    yield (result, events)

    val (result, events) = program.unsafeRunSync()
    assertEquals(result, "HI")
    assertEquals(events, Vector(Event.Started("upper", "hi"), Event.Succeeded("upper", "HI")))
  }

  test("instrumented agent fires failed and re-raises") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      base = WorkflowAgent.lift[IO, String, String]("boom")((_, _) => IO.raiseError(RuntimeException("kaboom")))
      wrapped = base.instrumented(listener(buf))
      scope <- AgentScope.create[IO]
      attempt <- wrapped.run("anything", scope).attempt
      events <- buf.get
    yield (attempt, events)

    val (attempt, events) = program.unsafeRunSync()
    assert(attempt.isLeft)
    assertEquals(events.size, 2)
    assertEquals(events.head, Event.Started("boom", "anything"))
    val Event.Failed(name, msg) = events.last: @unchecked
    assertEquals(name, "boom")
    assert(msg.contains("kaboom"))
  }

  test("instrumented agents nest inside Sequence + Parallel workflows") {
    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      l = listener(buf)
      writer = WorkflowAgent.liftScoped[IO, String, String]("writer")((s, _) =>
        IO.pure(s"draft of $s")
      ).instrumented(l)
      editor = WorkflowAgent.liftScoped[IO, String, String]("editor")((s, _) =>
        IO.pure(s.toUpperCase)
      ).instrumented(l)
      wf = writer.workflow.andThen(editor.workflow)
      scope <- AgentScope.create[IO]
      result <- wf.run("scala", scope)
      events <- buf.get
    yield (result, events)

    val (result, events) = program.unsafeRunSync()
    assertEquals(result, "DRAFT OF SCALA")
    // Each agent fires one started + one succeeded; order: writer then editor.
    val names = events.map {
      case Event.Started(n, _) => s"start:$n"
      case Event.Succeeded(n, _) => s"end:$n"
      case Event.Failed(n, _) => s"fail:$n"
    }
    assertEquals(names, Vector("start:writer", "end:writer", "start:editor", "end:editor"))
  }
