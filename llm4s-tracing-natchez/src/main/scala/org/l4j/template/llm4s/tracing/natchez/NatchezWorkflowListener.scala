package org.l4j.template.llm4s.tracing.natchez

import cats.Monad
import cats.syntax.all.*
import natchez.Trace
import natchez.TraceValue.{NumberValue, StringValue}
import org.l4j.template.llm4s.agentic.WorkflowListener

/** Natchez adapter for [[WorkflowListener]]. Each agent invocation is
  * surfaced as fields on the ambient span — adopters who want a real
  * per-agent span tree should wrap `agent.run(...)` in
  * `Trace[F].span(agent.name) { ... }` themselves; this listener stays
  * span-agnostic so it composes cleanly with that approach. */
final class NatchezWorkflowListener[F[_]: Monad: Trace] extends WorkflowListener[F]:

  override def onAgentStarted(name: String, input: Any): F[Unit] =
    Trace[F].put(
      "ai.event"     -> StringValue("workflow.agent.started"),
      "ai.agent.name" -> StringValue(name),
    )

  override def onAgentSucceeded(name: String, output: Any, durationNanos: Long): F[Unit] =
    Trace[F].put(
      "ai.event"       -> StringValue("workflow.agent.succeeded"),
      "ai.agent.name"  -> StringValue(name),
      "ai.duration.ns" -> NumberValue(durationNanos),
    )

  override def onAgentFailed(name: String, error: Throwable, durationNanos: Long): F[Unit] =
    Trace[F].put(
      "ai.event"           -> StringValue("workflow.agent.failed"),
      "ai.agent.name"      -> StringValue(name),
      "ai.duration.ns"     -> NumberValue(durationNanos),
      "ai.error.class"     -> StringValue(error.getClass.getName),
      "ai.error.message"   -> StringValue(Option(error.getMessage).getOrElse("")),
    ) >> Trace[F].attachError(error)

object NatchezWorkflowListener:
  def apply[F[_]: Monad: Trace]: NatchezWorkflowListener[F] =
    new NatchezWorkflowListener[F]
