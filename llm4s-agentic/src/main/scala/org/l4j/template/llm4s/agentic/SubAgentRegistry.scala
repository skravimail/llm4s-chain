package org.l4j.template.llm4s.agentic

final case class SubAgentRegistry[F[_], In, Out](
    agents: Map[String, Agent[F, In, Out]]
):
  def names: Set[String] =
    agents.keySet

  def get(name: String): Option[Agent[F, In, Out]] =
    agents.get(name)

object SubAgentRegistry:
  def of[F[_], In, Out](agents: Agent[F, In, Out]*): SubAgentRegistry[F, In, Out] =
    SubAgentRegistry(agents.map(agent => agent.name -> agent).toMap)

