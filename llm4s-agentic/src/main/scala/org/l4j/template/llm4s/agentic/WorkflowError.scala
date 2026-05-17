package org.l4j.template.llm4s.agentic

sealed abstract class WorkflowError(message: String, cause: Throwable | Null = null)
    extends RuntimeException(message, cause)

object WorkflowError:
  final case class MaxIterationsExceeded(maxIterations: Int)
      extends WorkflowError(s"LoopWorkflow exceeded maxIterations=$maxIterations")

  final case class MissingSubAgent(supervisorName: String, agentName: String)
      extends WorkflowError(
        s"SupervisorAgent '$supervisorName' could not find sub-agent '$agentName'"
      )
