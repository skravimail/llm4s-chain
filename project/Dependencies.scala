import sbt._

/** Centralised dependency versions and grouped library deps.
  *
  * Add new versions / groups here rather than declaring them inline in
  * build.sbt so that an update touches one file. Eleven sub-projects each
  * used to repeat the same cats-effect / upickle / testDeps boilerplate; the
  * `*Deps` groups below collapse that into one symbol per module.
  *
  * NOTE: this file is compiled by sbt's own (Scala 2) build classloader, so
  * use Scala 2 syntax here even though the project itself is Scala 3.
  */
object Dependencies {

  object V {
    val munit        = "1.1.1"
    val logback      = "1.4.14"
    val scalaLogging = "3.9.5"
    val uPickle      = "4.1.0"
    val catsEffect   = "3.5.4"
    val fs2          = "3.10.2"
    val sttp         = "3.10.3"
  }

  // Single-library aliases.
  val munit         = "org.scalameta" %% "munit"           % V.munit % Test
  val logback       = "ch.qos.logback" % "logback-classic" % V.logback
  val scalaLogging  = "com.typesafe.scala-logging" %% "scala-logging" % V.scalaLogging
  val uPickle       = "com.lihaoyi" %% "upickle"          % V.uPickle
  val catsEffect    = "org.typelevel" %% "cats-effect"    % V.catsEffect
  val fs2Core       = "co.fs2" %% "fs2-core"              % V.fs2
  val sttpCore      = "com.softwaremill.sttp.client3" %% "core" % V.sttp
  val sttpCatsAsync = "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % V.sttp

  // Grouped bundles.
  val testDeps: Seq[ModuleID]            = Seq(munit)
  val catsEffectDeps: Seq[ModuleID]      = Seq(catsEffect)
  val catsEffectFs2Deps: Seq[ModuleID]   = Seq(catsEffect, fs2Core)
  val catsEffectJsonDeps: Seq[ModuleID]  = Seq(catsEffect, uPickle)
  val sttpDeps: Seq[ModuleID]            = Seq(sttpCore, sttpCatsAsync)

  /** What every sub-project that runs tests with cats-effect / json needs. */
  val standardModuleDeps: Seq[ModuleID] = testDeps ++ catsEffectDeps ++ Seq(uPickle)
}
