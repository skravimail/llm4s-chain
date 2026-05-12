package org.l4j.template.llm4s.agentic

final case class PlanStep[In](
    agentName: String,
    input: Option[In] = None,
    outputKey: Option[String] = None,
)

final case class StepResult[In, Out](
    step: PlanStep[In],
    output: Out,
)

