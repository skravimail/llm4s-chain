// build.sbt — project root

ThisBuild / organization := "org.l4j.template"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"

val munitVersion       = "1.1.1"
val logbackVersion     = "1.4.14"
val scalaLogging       = "3.9.5"
val uPickleVersion     = "4.1.0"
val catsEffectVersion  = "3.5.4"
val fs2Version         = "3.10.2"
val sttpVersion        = "3.10.3"

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

lazy val llm4sRuntime = (project in file("llm4s-runtime"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-runtime",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
    ),
  )

lazy val llm4sStreaming = (project in file("llm4s-streaming"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-streaming",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
    ),
  )

lazy val llm4sOpenAiCompat = (project in file("llm4s-openai-compat"))
  .dependsOn(llm4sCore, llm4sStreaming)
  .settings(commonSettings)
  .settings(
    name := "llm4s-openai-compat",
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % sttpVersion,
    ),
  )

lazy val llm4sTools = (project in file("llm4s-tools"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-tools",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
    ),
  )

lazy val llm4sMemory = (project in file("llm4s-memory"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-memory",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    ),
  )

lazy val llm4sRag = (project in file("llm4s-rag"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-rag",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
    ),
  )

lazy val llm4sAgentic = (project in file("llm4s-agentic"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-agentic",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    ),
  )

lazy val llm4sMcp = (project in file("llm4s-mcp"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-mcp",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
    ),
  )

lazy val llm4sGuardrails = (project in file("llm4s-guardrails"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-guardrails",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
    ),
  )

lazy val llm4sStructured = (project in file("llm4s-structured"))
  .dependsOn(llm4sCore, llm4sRuntime, llm4sTools, llm4sMemory)
  .settings(commonSettings)
  .settings(
    name := "llm4s-structured",
    exportJars := true,
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
    libraryDependencies ++= testDeps ++ Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
    ),
  )

lazy val root = (project in file("."))
  .dependsOn(llm4sCore, llm4sMemory, llm4sRag, llm4sAgentic, llm4sMcp, llm4sGuardrails, llm4sRuntime, llm4sStreaming, llm4sOpenAiCompat, llm4sTools, llm4sStructured)
  .settings(commonSettings)
  .settings(
    name := "l4j-template",
    libraryDependencies ++= testDeps ++ Seq(
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      "com.typesafe.scala-logging" %% "scala-logging" % scalaLogging,
      "com.lihaoyi" %% "upickle" % uPickleVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % sttpVersion,
    ),
    Compile / mainClass := Some("org.l4j.template.demo.AgentDemoMain"),
    Compile / scalafmtOnCompile := false,
  )

compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges
