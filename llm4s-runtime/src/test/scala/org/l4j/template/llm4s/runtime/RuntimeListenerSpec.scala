package org.l4j.template.llm4s.runtime

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ChatResponse
import org.l4j.template.llm4s.core.FinishReason
import org.l4j.template.llm4s.core.JsonSchema
import org.l4j.template.llm4s.core.ToolCall
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.core.ToolSchema

class RuntimeListenerSpec extends FunSuite:

  enum Event:
    case Started
    case Completed(text: String)
    case ToolCalled(name: String)
    case ToolSucceeded(name: String)
    case ToolFailed(name: String, message: String)

  private def recordingListener(buf: Ref[IO, Vector[Event]]): RuntimeListener[IO] =
    new RuntimeListener[IO]:
      override def onChatStarted(request: ChatRequest): IO[Unit] =
        buf.update(_ :+ Event.Started)
      override def onChatCompleted(request: ChatRequest, text: String, turns: Int): IO[Unit] =
        buf.update(_ :+ Event.Completed(text))
      override def onToolCalled(call: ToolCall, ctx: InvocationContext): IO[Unit] =
        buf.update(_ :+ Event.ToolCalled(call.name))
      override def onToolSucceeded(call: ToolCall, ctx: InvocationContext, result: ToolResult): IO[Unit] =
        buf.update(_ :+ Event.ToolSucceeded(call.name))
      override def onToolFailed(call: ToolCall, ctx: InvocationContext, error: Throwable): IO[Unit] =
        buf.update(_ :+ Event.ToolFailed(call.name, Option(error.getMessage).getOrElse("")))

  test("listener observes chat start, tool call, and chat completion") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = Nil,
            toolCalls = List(ToolCall("ping", "{}", Some("c"))),
            finishReason = Some(FinishReason.ToolCalls),
          )
        ),
        ChatResponse(ChatMessage.AiMessage.from("done")),
      )
    )
    val kit = ToolKit[IO](
      List(ToolSchema("ping", "p", JsonSchema.ObjectSchema(Map.empty))),
      Map("ping" -> new ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          IO.pure(ToolResult.Text("pong"))),
    )

    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      listener = recordingListener(buf)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", kit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assertEquals(events.head, Event.Started)
    assertEquals(events.last, Event.Completed("done"))
    assert(events.contains(Event.ToolCalled("ping")))
    assert(events.contains(Event.ToolSucceeded("ping")))
  }

  test("listener observes tool failure when executor throws") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = Nil,
            toolCalls = List(ToolCall("boom", "{}", Some("c"))),
            finishReason = Some(FinishReason.ToolCalls),
          )
        ),
        ChatResponse(ChatMessage.AiMessage.from("recovered")),
      )
    )
    val kit = ToolKit[IO](
      List(ToolSchema("boom", "", JsonSchema.ObjectSchema(Map.empty))),
      Map("boom" -> new ToolExecutor[IO]:
        override def execute(call: ToolCall, ctx: InvocationContext): IO[ToolResult] =
          IO.raiseError(new RuntimeException("oops"))),
    )

    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      listener = recordingListener(buf)
      runtime = AiRuntime[IO](backend, RuntimeConfig(), listener)
      _ <- runtime.chat(None, "go", kit)
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assert(events.exists { case Event.ToolFailed("boom", msg) => msg == "oops"; case _ => false })
    assert(!events.exists { case Event.ToolSucceeded("boom") => true; case _ => false })
  }

  test("listener observes unknown-tool failure") {
    val backend = RecordingBackend(
      List(
        ChatResponse(
          ChatMessage.AiMessage(
            contents = Nil,
            toolCalls = List(ToolCall("ghost", "{}", Some("c"))),
            finishReason = Some(FinishReason.ToolCalls),
          )
        ),
        ChatResponse(ChatMessage.AiMessage.from("ok")),
      )
    )

    val program = for
      buf <- Ref.of[IO, Vector[Event]](Vector.empty)
      listener = recordingListener(buf)
      runtime = AiRuntime[IO](
        backend,
        RuntimeConfig(unknownToolPolicy = ToolErrorPolicy.SurfaceToModel),
        listener,
      )
      _ <- runtime.chat(None, "go", ToolKit.empty[IO])
      events <- buf.get
    yield events

    val events = program.unsafeRunSync()
    assert(events.exists { case Event.ToolFailed("ghost", _) => true; case _ => false })
  }

  private final case class RecordingBackend(
      scriptedResponses: List[ChatResponse]
  ) extends ChatBackend[IO]:
    @volatile private var remaining = scriptedResponses
    override def chat(request: ChatRequest): IO[ChatResponse] =
      IO {
        remaining match
          case head :: tail =>
            remaining = tail
            head
          case Nil =>
            throw RuntimeException("no scripted responses remaining")
      }
