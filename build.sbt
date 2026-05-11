// build.sbt — at project root

// =========== Project metadata & versions ===========
ThisBuild / organization := "org.llm4s.template"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS

// =========== Dependencies ===========
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s" % "0.1.1", // LLM library dependency
  "org.scalameta" %% "munit" % "1.1.1" % Test,

  // Logger dependencies
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // YAML configuration parsing
  "org.yaml" % "snakeyaml" % "2.2",

  // HTTP client for OMLX / OpenAI-compat local servers (bypasses Azure SDK's HTTPS-only check)
  "com.lihaoyi" %% "requests" % "0.9.0",

  // Scala-native JSON for the macro layer's typed return-value decoder
  "com.lihaoyi" %% "upickle" % "4.1.0",

  // langchain4j (used only as the underlying ChatModel transport; no AiServices/agentic)
  "dev.langchain4j" % "langchain4j"         % "1.14.1",
  "dev.langchain4j" % "langchain4j-open-ai" % "1.14.1",
)

// =========== Compiler options ===========
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding", "UTF-8",
  "-Wunused:imports",
)

// =========== Project definition ===========
lazy val root = (project in file("."))
  .settings(
    name := "llm4s-template",
    Compile / mainClass := Some("org.llm4s.template.Main"),
    Compile / scalafmtOnCompile := false,
  )

// =========== Best Practices ===========
compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges
