l4j-template
=================

Quickstart
----------
Scala 3 project demonstrating a compile-time, macro-generated AiService layer
on top of [langchain4j](https://github.com/langchain4j/langchain4j). The macro
emits a concrete `new T { ... }` for trait-shaped AI services — no
`java.lang.reflect.Proxy`, no runtime annotation scanning.

See [Readme_macro.md](./Readme_macro.md) for a walkthrough of the macro and the
`AgenticBridge` that plugs the generated impls into `langchain4j-agentic`'s
orchestrator.

Features
--------
- Scala 3 macro replaces langchain4j's reflective `AiServices` Proxy with
  compile-time class synthesis
- `{{var}}` placeholders in `@user` templates are validated against parameter
  names at compile time
- Typed return values decoded via uPickle (no Jackson POJOs)
- `@tool` methods on Scala classes are turned into langchain4j
  `ToolSpecification`s + direct dispatchers, also at compile time
- `AgenticBridge.asAgent[T](impl)` wraps a macro impl as an `AgentExecutor`
  suitable for `AgenticServices.{sequence,parallel,…}Builder().subAgents(...)`

Prerequisites
-------------
- JDK 21+
- SBT
- An OpenAI-compatible chat endpoint (real OpenAI, a local OMLX server, etc.)

Configure the model
-------------------
Every demo reads three env vars (with fall-throughs to a local OMLX server):

```bash
export LANGCHAIN4J_BASE_URL=http://localhost:8000/v1      # OpenAI-compat endpoint
export LANGCHAIN4J_API_KEY=sk-...                         # or any non-empty placeholder for local OMLX
export LANGCHAIN4J_MODEL=gemma-4-e4b-it-4bit              # model name on that endpoint
```

Run the demos
-------------
Three runnable mains live under `org.l4j.template.l4j_macro.demo.*`:

```bash
# 1. Macro AiService end-to-end: plain chat, typed CvReview return, tool-using tutor.
sbt "runMain org.l4j.template.l4j_macro.demo.MacroDemoMain"

# 2. Sequential agentic pipeline (CreativeWriter → AudienceEditor → StyleEditor).
sbt "runMain org.l4j.template.l4j_macro.demo.agentic.SequentialDemoMain"

# 3. Parallel agentic workflow (ManagerReviewer || TechnicalReviewer).
sbt "runMain org.l4j.template.l4j_macro.demo.agentic.ParallelDemoMain"
```

`sbt run` (no main class) launches `MacroDemoMain` by default.

Format & compile
----------------
```bash
sbt scalafmtAll
sbt compile
```

Layout
------
```
src/main/scala/org/l4j/template/l4j_macro/
  AiService.scala         — `materialize[T](model)` macro entry point
  Tools.scala / ToolKit   — `@tool` method harness builder
  Runtime.scala           — chat loop called from generated impls
  annotations.scala       — @system / @user / @tool / @param markers
  agentic/AgenticBridge   — adapter to langchain4j-agentic's orchestrator
  demo/                   — runnable examples
```

CI
--
Includes a GitHub Actions workflow that runs `sbt compile` and the formatter on
every push / PR.