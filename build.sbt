// build.sbt — project root

ThisBuild / organization := "org.l4j.template"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"

val munitVersion      = "1.1.1"
val logbackVersion    = "1.4.14"
val scalaLogging      = "3.9.5"
val uPickleVersion    = "4.1.0"
val catsEffectVersion = "3.5.4"
val sttpVersion       = "3.10.3"
val langChain4j       = "1.14.1"
val langChain4jAgentic = "1.14.1-beta24"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding", "UTF-8",
    "-Wunused:imports",
  ),
  Test / fork := false,
)

lazy val testDeps = Seq(
  "org.scalameta" %% "munit" % munitVersion % Test
)

lazy val llm4sCore = (project in file("llm4s-core"))
  .settings(commonSettings)
  .settings(
    name := "llm4s-core",
    exportJars := true,
    libraryDependencies ++= testDeps,
  )

lazy val llm4sOpenAiCompat = (project in file("llm4s-openai-compat"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-openai-compat",
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % sttpVersion,
    ),
  )

lazy val root = (project in file("."))
  .dependsOn(llm4sCore, llm4sOpenAiCompat)
  .settings(commonSettings)
  .settings(
    name := "l4j-template",
    libraryDependencies ++= testDeps ++ Seq(
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
      "dev.langchain4j" % "langchain4j" % langChain4j,
      "dev.langchain4j" % "langchain4j-open-ai" % langChain4j,
      "dev.langchain4j" % "langchain4j-agentic" % langChain4jAgentic,
    ),
    Compile / mainClass := Some("org.l4j.template.l4j_macro.demo.MacroDemoMain"),
    Compile / scalafmtOnCompile := false,
  )

compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges
