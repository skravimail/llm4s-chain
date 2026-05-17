package org.l4j.template.llm4s.agentic

import cats.MonadThrow
import cats.syntax.all.*

trait SupervisorPlanner[F[_], In, Out]:
  def plan(
      input: In,
      scope: AgentScope[F],
      registry: SubAgentRegistry[F, In, Out],
  ): F[List[PlanStep[In]]]

final class SupervisorAgent[F[_]: MonadThrow, In, Out](
    override val name: String,
    registry: SubAgentRegistry[F, In, Out],
    planner: SupervisorPlanner[F, In, Out],
    aggregate: (In, List[StepResult[In, Out]], AgentScope[F]) => F[Out],
) extends Agent[F, In, Out]:

  override def run(input: In, scope: AgentScope[F]): F[Out] =
    planner.plan(input, scope, registry).flatMap { steps =>
      steps.zipWithIndex.traverse { case (step, idx) => runStep(input, scope, idx)(step) }.flatMap { results =>
        scope.put(s"$name.plan", steps).flatMap(_ => aggregate(input, results, scope))
      }
    }

  private def runStep(input: In, scope: AgentScope[F], index: Int)(step: PlanStep[In]): F[StepResult[In, Out]] =
    registry.get(step.agentName) match
      case None =>
        MonadThrow[F].raiseError(WorkflowError.MissingSubAgent(name, step.agentName))
      case Some(agent) =>
        agent.run(step.input.getOrElse(input), scope).flatTap { output =>
          scope.put(step.outputKey.getOrElse(s"$name.$index.${step.agentName}"), output)
        }.map(output => StepResult(step, output))

object SupervisorAgent:
  def planner[F[_], In, Out](
      choose: (In, AgentScope[F], SubAgentRegistry[F, In, Out]) => F[List[PlanStep[In]]]
  ): SupervisorPlanner[F, In, Out] =
    new SupervisorPlanner[F, In, Out]:
      override def plan(
          input: In,
          scope: AgentScope[F],
          registry: SubAgentRegistry[F, In, Out],
      ): F[List[PlanStep[In]]] =
        choose(input, scope, registry)
