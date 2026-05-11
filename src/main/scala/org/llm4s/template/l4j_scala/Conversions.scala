package org.llm4s.template.l4j_scala

import java.util.{ List => JList, Map => JMap, Optional }
import java.util.concurrent.CompletableFuture
import scala.concurrent.{ Future, Promise }
import scala.jdk.CollectionConverters._

/**
 * Java/Scala interop helpers used across the wrapper layer.
 *
 * Boundary conventions:
 *   - Java `Optional` <-> Scala `Option`
 *   - Java `List`/`Collection`/`Iterable` -> Scala `Seq` (read-only consumption)
 *   - Scala `Seq`/`Iterable` -> Java `List` (when handing data to langchain4j builders)
 *   - `CompletableFuture[T]` -> `Future[T]`
 */
object Conversions {

  implicit final class OptionalOps[A](private val o: Optional[A]) extends AnyVal {
    def toOption: Option[A] = if (o.isPresent) Some(o.get()) else None
  }

  implicit final class OptionToOptional[A](private val o: Option[A]) extends AnyVal {
    def toOptional: Optional[A] = o match {
      case Some(a) => Optional.of(a)
      case None    => Optional.empty[A]()
    }
  }

  implicit final class JListOps[A](private val l: JList[A]) extends AnyVal {
    def toSeq: Seq[A] = l.asScala.toSeq
  }

  implicit final class SeqToJList[A](private val s: Seq[A]) extends AnyVal {
    def toJava: JList[A] = s.asJava
  }

  implicit final class JMapOps[K, V](private val m: JMap[K, V]) extends AnyVal {
    def toMap: Map[K, V] = m.asScala.toMap
  }

  implicit final class MapToJMap[K, V](private val m: Map[K, V]) extends AnyVal {
    def toJava: JMap[K, V] = m.asJava
  }

  /** Bridge a langchain4j CompletableFuture into a scala.concurrent.Future without blocking a thread. */
  implicit final class CompletableFutureOps[A](private val cf: CompletableFuture[A]) extends AnyVal {
    def toScalaFuture: Future[A] = {
      val p = Promise[A]()
      cf.whenComplete { (value, err) =>
        if (err != null) p.failure(err) else p.success(value)
        ()
      }
      p.future
    }
  }
}
