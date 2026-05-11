package org.llm4s.template.l4j_scala

import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.rag.RetrievalAugmentor
import dev.langchain4j.service.AiServices

import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.reflect.ClassTag

/**
 * Scala-friendly builder over langchain4j's `AiServices`.
 *
 * Replaces `AiServices.builder(classOf[Foo]).chatModel(m).tools(t).build()`
 * with `AiService.build[Foo](m, tools = Seq(t))`.
 */
object AiService {

  /** Optional knobs in case-class form, so callers don't fight the builder. */
  final case class Config(
    tools: Seq[AnyRef] = Seq.empty,
    memory: Option[ChatMemory] = None,
    retrievalAugmentor: Option[RetrievalAugmentor] = None,
  )

  /** Build a proxy implementation of trait/interface `T` backed by `model`. */
  def build[T: ClassTag](model: ChatModel, config: Config = Config()): T = {
    val cls = implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]]
    val b   = AiServices.builder[T](cls).chatModel(model)
    if (config.tools.nonEmpty) b.tools(config.tools: _*)
    config.memory.foreach(b.chatMemory)
    config.retrievalAugmentor.foreach(b.retrievalAugmentor)
    b.build()
  }

  /**
   * Call a synchronous `AiServices` method asynchronously. The method itself is sync;
   * this just shifts execution to the provided pool and wraps in `Future` for composition.
   *
   * Usage: `AiService.async { reviewer.reviewCv(cv, jd) }`
   */
  def async[A](call: => A)(implicit ec: ExecutionContext): Future[A] =
    Future(blocking(call))
}
