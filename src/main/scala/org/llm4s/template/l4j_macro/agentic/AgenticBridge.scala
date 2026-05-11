package org.llm4s.template.l4j_macro.agentic

import dev.langchain4j.agentic.internal.{ AgentExecutor, AgentUtil }

import scala.reflect.ClassTag

/**
 * Glue between the macro-generated AiService impls and langchain4j's
 * `AgenticServices` orchestrator.
 *
 * Why this exists:
 *
 *   - The orchestrator wires sub-agents by finding an `@Agent`-annotated
 *     method on the agent class via `Class#getMethods()`.
 *   - On a concrete Scala class, `@Agent` annotations declared on a trait do
 *     **not** propagate to the implementing class's method objects (a JVM
 *     interface-vs-class peculiarity), so the orchestrator can't find them.
 *   - On a `java.lang.reflect.Proxy`, `getMethods()` likewise returns
 *     annotation-less method objects, so the same fails.
 *
 * Workaround: look the method up on the trait class itself (where annotations
 * are visible), and hand the `(method, impl)` pair to langchain4j's
 * `nonAiAgentToExecutor`, which builds a fully-fledged `AgentExecutor`. The
 * `subAgents(Object...)` API special-cases `AgentExecutor` so it skips its
 * own (failing) annotation discovery for us.
 */
object AgenticBridge:

  /**
   * Wrap a typed macro impl as a langchain4j `AgentExecutor` suitable for
   * passing to `AgenticServices.{sequence,parallel,conditional,loop}Builder()
   * .subAgents(...)`.
   *
   * `T` must be a trait carrying a single `@Agent`-annotated method.
   */
  def asAgent[T](impl: T)(using ct: ClassTag[T]): AgentExecutor =
    val iface = ct.runtimeClass.asInstanceOf[Class[T]]
    require(iface.isInterface, s"AgenticBridge.asAgent requires a trait type, got ${iface.getName}")
    val agentMethod = AgentUtil.validateAgentClass(iface)
    AgentUtil.nonAiAgentToExecutor(impl, agentMethod)
