package org.l4j.template.llm4s.agentic

import cats.effect.Ref
import cats.effect.Sync
import cats.syntax.functor.*
import scala.reflect.ClassTag

final class AgentScope[F[_]: Sync] private (
    state: Ref[F, Map[String, Any]]
):

  def put[A](key: String, value: A): F[Unit] =
    state.update(_ + (key -> value))

  def get[A](key: String)(using tag: ClassTag[A]): F[Option[A]] =
    state.get.map(_.get(key).collect { case value if runtimeClass(tag).isInstance(value) =>
      value.asInstanceOf[A]
    })

  def snapshot: F[Map[String, Any]] =
    state.get

  private def runtimeClass[A](tag: ClassTag[A]): Class[?] =
    tag.runtimeClass match
      case java.lang.Integer.TYPE   => classOf[java.lang.Integer]
      case java.lang.Long.TYPE      => classOf[java.lang.Long]
      case java.lang.Double.TYPE    => classOf[java.lang.Double]
      case java.lang.Float.TYPE     => classOf[java.lang.Float]
      case java.lang.Boolean.TYPE   => classOf[java.lang.Boolean]
      case java.lang.Byte.TYPE      => classOf[java.lang.Byte]
      case java.lang.Short.TYPE     => classOf[java.lang.Short]
      case java.lang.Character.TYPE => classOf[java.lang.Character]
      case other                    => other

object AgentScope:
  def create[F[_]: Sync]: F[AgentScope[F]] =
    Ref.of[F, Map[String, Any]](Map.empty).map(new AgentScope[F](_))
