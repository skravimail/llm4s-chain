// build.sbt — at project root

// =========== Project metadata & versions ===========
ThisBuild / organization := "org.l4j.template"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS

// =========== Dependencies ===========
libraryDependencies ++= Seq(
  "org.scalameta" %% "munit" % "1.1.1" % Test,

  // Logger dependencies
  "ch.qos.logback" % "logback-classic" % "1.4.14",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",

  // Scala-native JSON for the macro layer's typed return-value decoder
  "com.lihaoyi" %% "upickle" % "4.1.0",

  // langchain4j: ChatModel transport for the macro layer's Runtime, plus the
  // agentic orchestrator (sequenceBuilder/parallelBuilder/...) which the
  // AgenticBridge plugs macro impls into via java.lang.reflect.Proxy.
  "dev.langchain4j" % "langchain4j"         % "1.14.1",
  "dev.langchain4j" % "langchain4j-open-ai" % "1.14.1",
  "dev.langchain4j" % "langchain4j-agentic" % "1.14.1-beta24",
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
    name := "l4j-template",
    Compile / mainClass := Some("org.l4j.template.l4j_macro.demo.MacroDemoMain"),
    Compile / scalafmtOnCompile := false,
  )

// =========== Best Practices ===========
compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges