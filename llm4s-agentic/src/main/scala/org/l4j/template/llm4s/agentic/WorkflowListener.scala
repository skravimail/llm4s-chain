package org.l4j.template.llm4s.agentic

import cats.Applicative
import cats.effect.kernel.Sync
import cats.syntax.all.*

/** Observability hook for workflow-style agents.
  *
  * Sibling to `RuntimeListener` (chat / tool side) and `GuardrailListener`
  * (guardrail side). Each fires when a [[WorkflowAgent]] decorated via
  * `instrumented(listener)` runs — making any pipeline (sequence, parallel,
  * conditional, loop, supervisor) automatically observable as long as each
  * leaf agent is wrapped.
  *
  * Input / output are typed as `Any` because a single listener instance is
  * expected to serve agents with heterogeneous payload types. Adopters that
  * need types can downcast on `name`.
  */
trait WorkflowListener[F[_]]:
  def onAgentStarted(name: String, input: Any): F[Unit]
  def onAgentSucceeded(name: String, output: Any, durationNanos: Long): F[Unit]
  def onAgentFailed(name: String, error: Throwable, durationNanos: Long): F[Unit]

object WorkflowListener:
  def noop[F[_]](using F: Applicative[F]): WorkflowListener[F] = new WorkflowListener[F]:
    override def onAgentStarted(n: String, i: Any): F[Unit] = F.unit
    override def onAgentSucceeded(n: String, o: Any, d: Long): F[Unit] = F.unit
    override def onAgentFailed(n: String, e: Throwable, d: Long): F[Unit] = F.unit

  abstract class Default[F[_]](using F: Applicative[F]) extends WorkflowListener[F]:
    override def onAgentStarted(n: String, i: Any): F[Unit] = F.unit
    override def onAgentSucceeded(n: String, o: Any, d: Long): F[Unit] = F.unit
    override def onAgentFailed(n: String, e: Throwable, d: Long): F[Unit] = F.unit

/** Wraps a `WorkflowAgent` so each `run` invocation fires
  * `onAgentStarted` / `onAgentSucceeded` / `onAgentFailed` and reports the
  * wall-clock duration. */
final class InstrumentedAgent[F[_], In, Out](
    underlying: WorkflowAgent[F, In, Out],
    listener: WorkflowListener[F],
)(using F: Sync[F])
    extends WorkflowAgent[F, In, Out]:

  override val name: String = underlying.name

  override def run(input: In, scope: AgentScope[F]): F[Out] =
    for
      _ <- listener.onAgentStarted(name, input)
      start <- F.delay(System.nanoTime())
      attempted <- underlying.run(input, scope).attempt
      duration = System.nanoTime() - start
      out <- attempted match
        case Right(o) => listener.onAgentSucceeded(name, o, duration).as(o)
        case Left(e)  => listener.onAgentFailed(name, e, duration) >> F.raiseError(e)
    yield out

extension [F[_]: Sync, In, Out](self: WorkflowAgent[F, In, Out])
  /** Wrap this agent so each invocation fires events on `listener`. The
    * returned agent is itself a `WorkflowAgent`, so it composes inside
    * `Sequence` / `Parallel` / `Conditional` / `Loop` workflows
    * automatically. */
  def instrumented(listener: WorkflowListener[F]): WorkflowAgent[F, In, Out] =
    new InstrumentedAgent[F, In, Out](self, listener)
