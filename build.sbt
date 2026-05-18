// build.sbt — project root
//
// Versions and grouped library dependencies live in project/Dependencies.scala
// (PR-15). Don't add new versions inline here.

import Dependencies.*

ThisBuild / organization := "org.l4j.template"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-encoding", "UTF-8",
    "-Wunused:imports",
    "-Wvalue-discard",
    "-Wnonunit-statement",
    // `-Xfatal-warnings` deliberately *not* set yet: existing code emits
    // a few benign anonymous-class-at-inline-site warnings; turning them
    // fatal would block useful PRs. See CODE_REVIEW.md PR-15.
  ),
  Test / fork := false,
  // Flat layering avoids sbt's hierarchical classloader splitting cats-effect
  // / fs2 classes across loaders inside test runs, which otherwise produces
  // spurious `LinkageError`s on Scala 3 inline-derived givens. Set once here
  // instead of being copy-pasted into every sub-project (PR-15).
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
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
    libraryDependencies ++= standardModuleDeps,
  )

lazy val llm4sStreaming = (project in file("llm4s-streaming"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-streaming",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectFs2Deps,
  )

lazy val llm4sOpenAiCompat = (project in file("llm4s-openai-compat"))
  .dependsOn(llm4sCore, llm4sStreaming)
  .settings(commonSettings)
  .settings(
    name := "llm4s-openai-compat",
    libraryDependencies ++= testDeps ++ catsEffectFs2Deps ++ Seq(uPickle) ++ sttpDeps,
  )

lazy val llm4sTools = (project in file("llm4s-tools"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-tools",
    exportJars := true,
    libraryDependencies ++= standardModuleDeps,
  )

lazy val llm4sMemory = (project in file("llm4s-memory"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-memory",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectDeps,
  )

lazy val llm4sRag = (project in file("llm4s-rag"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-rag",
    exportJars := true,
    libraryDependencies ++= standardModuleDeps,
  )

lazy val llm4sAgentic = (project in file("llm4s-agentic"))
  .dependsOn(llm4sCore)
  .settings(commonSettings)
  .settings(
    name := "llm4s-agentic",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectDeps,
  )

lazy val llm4sMcp = (project in file("llm4s-mcp"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-mcp",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectDeps ++ Seq(uPickle, sttpCore),
  )

lazy val llm4sGuardrails = (project in file("llm4s-guardrails"))
  .dependsOn(llm4sCore, llm4sRuntime)
  .settings(commonSettings)
  .settings(
    name := "llm4s-guardrails",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectDeps,
  )

lazy val llm4sStructured = (project in file("llm4s-structured"))
  .dependsOn(llm4sCore, llm4sRuntime, llm4sTools, llm4sMemory)
  .settings(commonSettings)
  .settings(
    name := "llm4s-structured",
    exportJars := true,
    libraryDependencies ++= standardModuleDeps,
  )

lazy val llm4sDsl = (project in file("llm4s-dsl"))
  .dependsOn(llm4sCore, llm4sRuntime, llm4sStructured, llm4sRag)
  .settings(commonSettings)
  .settings(
    name := "llm4s-dsl",
    exportJars := true,
    libraryDependencies ++= standardModuleDeps,
  )

lazy val llm4sTracingNatchez = (project in file("llm4s-tracing-natchez"))
  .dependsOn(llm4sCore, llm4sRuntime, llm4sGuardrails, llm4sAgentic, llm4sOpenAiCompat)
  .settings(commonSettings)
  .settings(
    name := "llm4s-tracing-natchez",
    exportJars := true,
    libraryDependencies ++= testDeps ++ catsEffectDeps ++ Seq(natchezCore),
  )

/** Pseudo-aggregate that lets CI compile/test everything with a single task
  * (`sbt all/test`) without dragging the demo runner's runtime deps in. */
lazy val all = (project in file(".all"))
  .settings(commonSettings)
  .settings(
    name := "llm4s-all",
    publish / skip := true,
  )
  .aggregate(
    llm4sCore, llm4sRuntime, llm4sStreaming, llm4sOpenAiCompat,
    llm4sTools, llm4sMemory, llm4sRag, llm4sAgentic, llm4sMcp,
    llm4sGuardrails, llm4sStructured, llm4sTracingNatchez,
    llm4sDsl,
  )

lazy val root = (project in file("."))
  .dependsOn(llm4sCore, llm4sMemory, llm4sRag, llm4sAgentic, llm4sMcp, llm4sGuardrails, llm4sRuntime, llm4sStreaming, llm4sOpenAiCompat, llm4sTools, llm4sStructured, llm4sDsl)
  .settings(commonSettings)
  .settings(
    name := "l4j-template",
    libraryDependencies ++= testDeps ++ Seq(
      logback,
      scalaLogging,
      uPickle,
      catsEffect,
      sttpCore,
      sttpCatsAsync,
      snakeYaml,
    ),
    Compile / mainClass := Some("org.l4j.template.demo.AgentDemoMain"),
    Compile / scalafmtOnCompile := false,
  )

compileOrder := CompileOrder.Mixed
Global / onChangedBuildSource := ReloadOnSourceChanges
