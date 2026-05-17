l4j-template
=================

Scala 3, cats-effect / sttp building blocks for LLM applications:
provider-neutral chat + tool calling, typed structured outputs, streaming,
memory, RAG, agentic workflows, MCP tools, guardrails, and multimodals.

- **Usage & module guide:** [docs/USAGE.md](./docs/USAGE.md)
- **Delivery history & PR plan:** [CHANGELOG.md](./CHANGELOG.md)
- **Open review items / tech-debt punch list:** [CODE_REVIEW.md](./CODE_REVIEW.md)

Features
--------
- Provider-neutral core ADTs in `llm4s-core` and an OpenAI-compatible HTTP
  backend in `llm4s-openai-compat`, plus a streaming facade/decoder layer.
- `AiRuntime` chat-and-tool loop with compile-time tool argument decoding.
- `AiAgent[F]` builder over the runtime for plain chat, typed structured
  output, and tool-using chat without macros, `unsafeRunSync`, or
  `@experimental`.
- Streaming runtime (text deltas, tool-call deltas, completion markers).
- Session memory + windowing.
- RAG primitives + advanced retrieval composition.
- Deterministic workflows (sequence / parallel / conditional / loop) and a
  supervisor planner.
- MCP JSON-RPC client (stdio + HTTP) adapted to native tool kits.
- Input/output/tool guardrails and a moderation contract.

Prerequisites
-------------
- JDK 17+
- SBT
- An OpenAI-compatible chat endpoint (real OpenAI, a local OMLX server, etc.)

Configure the model
-------------------
Demos read `config.yaml` plus provider-specific API key env vars:

```bash
export OMLX_API_KEY=local-dev-placeholder
# or OPENAI_API_KEY / GOOGLE_API_KEY / IBM_API_KEY depending on config.yaml
```

Then edit `config.yaml` to choose the provider, model, endpoint override, and
timeouts.

Run the demos
-------------
Three runnable mains live under `org.l4j.template.demo.*`:

```bash
# 1. AiAgent end-to-end: plain chat, typed CvReview return, tool-using tutor.
sbt "runMain org.l4j.template.demo.AgentDemoMain"

# 2. Sequential agentic pipeline (writer -> audience editor -> style editor).
sbt "runMain org.l4j.template.demo.agentic.SequentialDemoMain"

# 3. Parallel agentic workflow (manager reviewer || technical reviewer).
sbt "runMain org.l4j.template.demo.agentic.ParallelDemoMain"
```

`sbt run` (no main class) launches `AgentDemoMain` by default.

Format & compile
----------------
```bash
sbt scalafmtAll
sbt compile
```

Layout
------
```
llm4s-core/             provider-neutral protocol ADTs
llm4s-openai-compat/    OpenAI-compatible HTTP backend + streaming decoder
llm4s-runtime/          chat loop, tool loop, runtime config
llm4s-tools/            tool schema + argument decoding derivation
llm4s-structured/       typed structured outputs + AiAgent builder
llm4s-streaming/        streaming runtime and events
llm4s-memory/           session and window memory
llm4s-rag/              embeddings, stores, retrieval, augmentation
llm4s-agentic/          deterministic workflows + supervisor planner
llm4s-mcp/              MCP JSON-RPC client (stdio + HTTP)
llm4s-guardrails/       guardrails, moderation, retry policy
src/                    demos
```

CI
--
Includes a GitHub Actions workflow that runs formatting plus the aggregate test
suite on every push / PR.
