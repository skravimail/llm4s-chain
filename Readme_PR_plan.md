# Native Scala 3 Framework-Parity PR Plan

This document tracks the roadmap for replacing the LangChain4j dependency surface with native Scala 3 modules on the `l4jOnly_codex` branch.

The target is framework parity, not full ecosystem parity. The goal is to support the main LangChain4j-style application patterns natively:

- trait-based AI services
- prompt templating
- typed structured outputs
- tool calling
- streaming
- chat memory
- RAG
- deterministic and supervisor-style agentic workflows
- MCP-backed tools
- guardrails and moderation hooks
- multimodal request/result abstractions

The target does not require replicating every provider, vector store, reranker, image model, or long-tail integration that LangChain4j supports.

## Working Branch

- Branch: `l4jOnly_codex`
- Workspace: `/Users/alpha/AI_ML/llm4s-template`
- Scala: `3.3.4`
- Build: SBT multi-project
- Test framework: `munit`

## Dependency Direction

The native implementation is built around small Scala modules:

- `llm4s-core`: provider-neutral protocol ADTs.
- `llm4s-openai-compat`: OpenAI-compatible HTTP/SSE backend.
- `llm4s-runtime`: runtime chat loop and tool-call orchestration.
- `llm4s-tools`: Scala 3 tool schema and argument derivation.
- `llm4s-macros`: compile-time AI service materialization.
- `llm4s-structured`: typed structured-output support.
- `llm4s-streaming`: streaming runtime and events.
- `llm4s-memory`: session/conversation memory.
- `llm4s-rag`: embeddings, stores, retrieval, augmentation, and advanced RAG pipeline.
- `llm4s-agentic`: deterministic workflows and supervisor orchestration.
- `llm4s-mcp`: MCP JSON-RPC client, transports, and tool adapter.

For architecture and feature usage examples, see `Usage_Readme.md`.


Recommended implementation stack:

- `cats-effect` for effects, resources, concurrency, and blocking boundaries.
- `fs2` for streaming.
- `sttp` for HTTP/SSE clients.
- `uPickle`/`ujson` for JSON codecs and JSON values.
- `munit` for unit tests.

## Validation Policy

Each PR should be committed separately after its unit tests pass.

Preferred PR gate:

```bash
env SBT_OPTS=-Dsbt.boot.directory=/Users/alpha/AI_ML/llm4s-template/.sbt-boot\ -Dsbt.ivy.home=/Users/alpha/AI_ML/llm4s-template/.ivy2 COURSIER_CACHE=/Users/alpha/AI_ML/llm4s-template/.coursier sbt 'llm4sCore/test' 'llm4sMemory/test' 'llm4sRag/test' 'llm4sAgentic/test' 'llm4sMcp/test' 'llm4sRuntime/test' 'llm4sStreaming/test' 'llm4sOpenAiCompat/test' 'llm4sTools/test' 'llm4sStructured/test' 'llm4sMacros/test'
```

Focused PR gates should also run the touched modules directly, for example:

```bash
sbt 'llm4sMcp/test'
```

Known environment note:

- SBT may need repo-local cache settings because global SBT state can load unrelated plugins or fail to bind local IPC sockets inside the sandbox.
- The repo uses local cache paths under `/Users/alpha/AI_ML/llm4s-template`.

## Completed PRs

### PR-1: Split Build And Add Core ADTs

Commit: `22f4f94`  
Status: Complete

Purpose:

- Convert the project to an SBT multi-project layout.
- Establish provider-neutral native protocol types.
- Create the core data model that all later modules use.

Key scope:

- `llm4s-core`
- `ChatMessage`
- `ChatRequest`
- `ChatResponse`
- `AiContent`
- `ToolCall`
- `ToolResult`
- `ToolSchema`
- `JsonSchema`
- `ResponseFormat`
- `Usage`
- `FinishReason`
- `ModelCapabilities`

Acceptance:

- Core types compile and have focused unit coverage.
- No runtime behavior is replaced yet.

### PR-2: Add OpenAI-Compatible Backend

Commit: `d126b0c`  
Status: Complete

Purpose:

- Add the first native provider backend.
- Use the OpenAI-compatible wire shape as the pragmatic baseline for chat and tool calls.

Key scope:

- `llm4s-openai-compat`
- native request encoder
- native response decoder
- transport abstraction
- OpenAI-compatible chat backend

Acceptance:

- Plain chat requests encode correctly.
- Tool schemas encode correctly.
- Assistant text, tool calls, usage, and finish reasons decode correctly.

### PR-3: Add Native AI Runtime

Commit: `effe051`  
Status: Complete

Purpose:

- Replace low-level LangChain4j chat-loop behavior with a native runtime.
- Preserve current model/tool turn semantics.

Key scope:

- `llm4s-runtime`
- `AiRuntime`
- `ToolLoop`
- `ToolKit`
- `ToolExecutor`
- `InvocationContext`
- `RuntimeConfig`

Acceptance:

- Plain assistant responses work.
- Tool calls execute and continue the conversation.
- Runtime fails when `maxTurns` is exceeded.

### PR-4: Add Native Tool Derivation

Commit: `aec9865`  
Status: Complete

Purpose:

- Replace LangChain4j tool schema usage with native Scala 3 schema and argument decoding.

Key scope:

- `llm4s-tools`
- `SchemaEncoder`
- `ValueDecoder`
- `ToolDefinition`

Supported types:

- primitives
- nested case classes
- enums
- `Option`
- `List`

Acceptance:

- Tool schema derivation supports nested products, optional fields, lists, and enums.
- Tool execution decodes structured JSON arguments before invoking Scala functions.

### PR-5: Add Native Macro AI Services

Commit: `35c1d95`  
Status: Complete

Purpose:

- Migrate macro-generated AI service traits onto the native runtime.
- Preserve the current annotation-driven user API.

Key scope:

- `llm4s-macros`
- `AiService`
- macro materialization
- compile-time prompt placeholder validation

Preserved API:

- `@system`
- `@user`
- `@tool`
- `@param`

Acceptance:

- Materialized services execute prompt templates against `ChatBackend[IO]`.
- Compile-time validation rejects invalid prompt placeholders.
- Native tool calls work from materialized services.

### PR-6: Add Structured Output Support

Commit: `8f93275`  
Status: Complete

Purpose:

- Add typed structured outputs without relying only on free-form JSON prompting.

Key scope:

- `llm4s-structured`
- `StructuredCodec`
- `StructuredOutputRuntime`
- JSON Schema response format support
- typed decoding flow

Acceptance:

- Structured codecs derive schema.
- Runtime requests JSON Schema output when available.
- Typed case-class results decode from assistant JSON.

### PR-7: Add Streaming Runtime And SSE Decoding

Commit: `2532e70`  
Status: Complete

Purpose:

- Add native streaming support for OpenAI-compatible providers.

Key scope:

- `llm4s-streaming`
- `StreamingChatBackend`
- `StreamingAiRuntime`
- `StreamEvent`
- token stream helper
- OpenAI-compatible SSE decoder

Supported stream events:

- text deltas
- tool-call deltas
- completion/final markers

Acceptance:

- Streaming runtime builds the initial request.
- OpenAI-compatible streaming backend sends `stream=true`.
- SSE decoder maps emitted events into native stream events.

### PR-8: Add Native Chat Memory

Commit: `686364c`  
Status: Complete

Purpose:

- Add native session/conversation memory.
- Wire memory through runtime, structured output, and macros.

Key scope:

- `llm4s-memory`
- `MemoryId`
- `ChatMemory`
- `InMemoryChatMemory`
- `MessageWindowMemory`
- memory-aware `AiRuntime`
- memory-aware `StructuredOutputRuntime`
- memory-aware macro materialization overload

Acceptance:

- Memory stores messages per id.
- Window memory trims to the configured size.
- Runtime persists conversational history.
- Materialized services can use session memory across calls.

Validation:

- `llm4sMemory/test`
- `llm4sRuntime/test`
- `llm4sStructured/test`
- `llm4sMacros/test`
- explicit all-module sweep passed.

### PR-9: Add Native RAG Phase One

Commit: `1c6e136`  
Status: Complete

Purpose:

- Add first-class RAG abstractions and a usable in-memory retrieval path.
- Add a pragmatic `pgvector` adapter boundary.

Key scope:

- `llm4s-rag`
- `EmbeddingModel`
- `EmbeddingVector`
- `EmbeddingRecord`
- `EmbeddingStore`
- `InMemoryEmbeddingStore`
- `ContentRetriever`
- `EmbeddingContentRetriever`
- `RetrievedSource`
- `RetrievalAugmentor`
- `AugmentedChatRequest`
- `PgVectorEmbeddingStore`
- `PgVectorConfig`

Acceptance:

- In-memory embedding store ranks by cosine similarity.
- Retriever embeds query before searching.
- Augmentor rewrites the last user message with retrieved context.
- Sources are returned with the augmented request.
- `pgvector` config rejects unsafe SQL identifiers.

Validation:

- `llm4sRag/test`
- root `sbt test`
- explicit all-module sweep passed.

### PR-10: Add Advanced RAG Pipeline

Commit: `e7d2266`  
Status: Complete

Purpose:

- Add advanced retrieval composition on top of the phase-one contracts.

Key scope:

- `QueryTransformer`
- `QueryRouter`
- `ContentAggregator`
- `ReRanker`
- `AdvancedContentRetriever`

Supported advanced RAG behavior:

- multi-query retrieval
- multi-retriever routing
- source de-duplication
- keep-best-score aggregation
- reranking hook
- final result limiting

Acceptance:

- Aggregator deduplicates sources by id and keeps the highest score.
- Advanced retriever transforms, routes, aggregates, reranks, and limits results.

Validation:

- `llm4sRag/test`
- explicit all-module sweep passed.

### PR-11: Add Native Deterministic Workflows

Commit: `59ba53a`  
Status: Complete

Purpose:

- Replace the first part of `langchain4j-agentic` with typed deterministic Scala workflows.

Key scope:

- `llm4s-agentic`
- `Agent`
- `AgentScope`
- `Workflow`
- `SequenceWorkflow`
- `ParallelWorkflow`
- `ConditionalWorkflow`
- `LoopWorkflow`

Design choice:

- Use typed Scala workflow composition instead of Java reflection or runtime annotation discovery.
- Store scoped outputs through `AgentScope`.

Acceptance:

- Sequence passes each output to the next step.
- Parallel runs independent branches over the same input.
- Conditional selects the matching branch.
- Loop repeats until the predicate stops.
- Loop fails when max iterations are exceeded.

Validation:

- `llm4sAgentic/test`
- explicit all-module sweep passed.

### PR-12: Add Supervisor Agent Orchestration

Commit: `501a20a`  
Status: Complete

Purpose:

- Add supervisor-style dynamic orchestration on top of typed deterministic agents.

Key scope:

- `PlanStep`
- `StepResult`
- `SubAgentRegistry`
- `SupervisorPlanner`
- `SupervisorAgent`

Supported behavior:

- planner returns named sub-agent steps
- step input can override the original input
- step output can be stored under explicit output keys
- supervisor aggregates step results
- missing planned sub-agent fails clearly

Acceptance:

- Supervisor invokes planned sub-agents dynamically.
- Supervisor records step outputs in `AgentScope`.
- Supervisor fails when a plan references an unknown sub-agent.

Validation:

- `llm4sAgentic/test`
- explicit all-module sweep passed.

### PR-13: Add Native MCP Tool Integration

Commit: `f3c5744`  
Status: Complete

Purpose:

- Add native MCP client support and expose MCP tools as native `ToolKit` entries.

Key scope:

- `llm4s-mcp`
- `McpTransport`
- `StdioMcpTransport`
- `HttpMcpTransport`
- `McpClient`
- `McpProtocol`
- `McpTool`
- `McpToolList`
- `McpToolCallResult`
- `McpSchemaConverter`
- `McpToolProvider`

Supported MCP methods:

- `tools/list`
- `tools/call`

Supported MCP fields:

- tool `name`
- tool `description`
- tool `inputSchema`
- result `content`
- result `structuredContent`
- result `isError`
- pagination via `nextCursor`

Acceptance:

- Client lists all MCP tools across paginated responses.
- Tool provider adapts MCP tools into native `ToolKit` schemas/executors.
- Tool call results prefer `structuredContent` when present.
- Stdio transport writes JSON-RPC requests and decodes response results.

Validation:

- `llm4sMcp/test` passed.
- Full explicit all-module sweep passed before PR-14 started.

### PR-14: Add Guardrails And Moderation Hooks

Commit: `3a3c185`  
Status: Complete

Purpose:

- Add reusable safety and policy interception points around model calls, tool calls, structured outputs, and final responses.
- Provide moderation adapter contracts without tying the core to one provider.

Key scope:

- `llm4s-guardrails`
- `GuardrailResult`
- `GuardrailViolation`
- `InputGuardrail[F]`
- `OutputGuardrail[F]`
- `ToolGuardrail[F]`
- `GuardrailChain[F]`
- `ModerationModel[F]`
- `ModerationResult`
- `RetryPolicy`
- `GuardedChatBackend[F]`
- `GuardedToolKit`

Implemented behavior:

- Input guardrails run before backend calls.
- Output guardrails run after backend responses.
- Tool guardrails can allow or block tool calls.
- Blocked tool calls return an error `ToolResult`.
- Moderation hooks can be plugged into input and output guardrails.
- Retry policy retries recoverable backend failures.
- Violations preserve structured metadata.

Acceptance:

- Guarded backend blocks unsafe input before invoking the underlying backend.
- Guarded backend blocks unsafe output after invoking the underlying backend.
- Moderation input guardrail blocks flagged requests.
- Guarded tool kit returns an error result when a tool call is blocked.
- Retry policy retries recoverable backend failures.

Validation:

- `llm4sGuardrails/test`
- explicit all-module sweep passed.

### PR-15: Add Multimodal Cleanup And Secondary Capability Pass

Commit: `befe22e`  
Status: Complete

Purpose:

- Finish the framework-parity pass by tightening multimodal request/result handling and cleaning up remaining native module boundaries.

Key scope:

- `AiContent.File`
- `AiContent.requiredCapabilities`
- `ChatRequest.requiredCapabilities`
- `ModelCapabilities.validate`
- `UnsupportedModelCapabilities`
- `llm4s-openai-compat`
- `llm4s-mcp`

Implemented behavior:

- Text, image, and file inputs are represented in provider-neutral core ADTs.
- Request capability requirements are derived from multimodal content, tools, and JSON Schema response format.
- Unsupported provider capabilities fail clearly through `ModelCapabilities.validate`.
- OpenAI-compatible wire encoder emits multimodal user content parts for text, images, and files.
- MCP `resource` content can convert into native file content.

Acceptance:

- Core tests cover capability detection and unsupported capability reporting.
- OpenAI-compatible tests cover multimodal request encoding.
- MCP tests cover resource-to-file content conversion.

Validation:

- `llm4sCore/test`
- `llm4sOpenAiCompat/test`
- `llm4sMcp/test`
- explicit all-module sweep passed.

### PR-16: Add Non-Macro `AiAgent` Ergonomics Layer

Commit: _pending_  
Status: In progress  
Branch: `pr16_non_macro_aiagent`

Purpose:

- Provide a non-macro, plain-Scala-3 alternative to `AiService.materialize[T]`
  that delivers the same three call shapes (plain chat, typed structured
  output, tool-using chat) without the macro tax.
- Eliminate the `unsafeRunSync` deadlock surface noted in
  `Module Review Findings → llm4s-macros #1` for users who don't need
  the annotated-trait API.
- Avoid the `String.replace`-based `{{placeholder}}` substitution (and the
  `.toString` garbage-prompt risk from `Module Review Findings → llm4s-macros #2`)
  in favour of native Scala 3 string interpolation.
- Drop the `@experimental` requirement that the macro inherits from
  `Symbol.newClass` reflection.

Background:

- `AiService.materialize[T]` reads `@system`/`@user` annotations off an
  abstract trait and synthesises an implementation that calls
  `AiRuntime.chat` (or `StructuredOutputRuntime.chat`) and then forces the
  result with `unsafeRunSync()(using IORuntime.global)`. That hides effect
  composition from the caller and is a documented deadlock risk on the
  calling thread when the demo is embedded in another `IO` program.
- Prompt parameters are substituted via `userTemplate.replace("{{name}}", v.toString)`,
  so `List(...)`, `Option(...)`, and case classes produce ugly,
  framework-y prompt strings.
- The three demo methods (`Assistant.ask`, `Reviewer.review`, `Tutor.explain`)
  are each 3 lines of effectful glue over the existing native runtime; the
  trait-plus-annotation framing pays for itself only when there are many
  methods sharing the same shape, which is not this demo.

Design:

- Add `llm4s-structured/.../AiAgent.scala`: a small generic builder around
  the existing `AiRuntime` and `StructuredOutputRuntime`. Placement is in
  `llm4s-structured` (not `llm4s-runtime`) because the builder needs both
  modules and `llm4s-runtime` does not depend on `llm4s-structured` —
  `llm4s-structured` is the existing convergence point that already
  depends on runtime + tools + memory.
  - `final class AiAgent[F[_]: MonadThrow](backend, tools, config)`
  - `def chat(system: String, user: String): F[String]`
  - `def chatAs[A](system, user)(using StructuredCodec[A]): F[A]`
    — distinct method name to avoid overload-resolution ambiguity with the
    plain `chat`.
  - `def chatWithMemory[Id]` / `def chatAsWithMemory[A, Id]` for the
    memory-aware variants.
  - `def withTools(tk: ToolKit[F]): AiAgent[F]`
  - `def withConfig(c: RuntimeConfig): AiAgent[F]`
- Add `src/main/scala/org/l4j/template/demo/AgentDemoMain.scala`:
  an `IOApp.Simple` parallel to `MacroDemoMain` that delivers the same
  three demo flows (plain chat, typed `CvReview`, tool-using `define`) using
  plain `def` functions and Scala 3 `s"..."` interpolation — no traits,
  no annotations, no `@experimental`, no `unsafeRunSync` inside method
  bodies.
- Keep `MacroDemoMain` exactly as it is so the two styles remain available
  side-by-side for users to compare.

What's gained:

- Methods return `F[A]` (here `IO[A]`), so callers compose with the rest of
  cats-effect normally; `unsafeRun*` only ever appears in the `IOApp` entry.
- Prompt interpolation is compiler-checked; placeholders cannot drift from
  parameters.
- Step-into debugging works — no synthesised class bytecode.
- No `@experimental`; uses only stable Scala 3 features.

What's given up:

- The "annotated trait → implementation" declarative feel where prompt,
  system message, and tools live in one block. The macro is shorter when
  there are many methods sharing the same shape; for diverse call shapes
  the explicit builder is strictly clearer.

Key scope:

- `llm4s-structured`
- `AiAgent[F]`
- `AgentDemoMain` (demo only — no module changes downstream).

Acceptance:

- `AiAgent.chat(system, user)` returns `F[String]` and uses the existing
  `AiRuntime` loop (tool calls, max-turn limit, finish-reason handling all
  inherited).
- `AiAgent.chatAs[A](system, user)` returns `F[A]` via existing
  `StructuredOutputRuntime` semantics.
- `AgentDemoMain` runs against an OpenAI-compatible local server and prints
  the same three sections (`[1/3] Plain chat`, `[2/3] Typed return`,
  `[3/3] Tool-using agent`) as `MacroDemoMain`.
- `llm4sStructured/test` continues to pass; new tests cover the builder
  surface (`chat` plain text, `chatAs` typed decode, `withTools` immutability,
  `withConfig` immutability + max-turn enforcement).

Related findings closed (for non-macro callers):

- `Module Review Findings → llm4s-macros #1`: deadlock-prone
  `unsafeRunSync` — non-macro path stays in `F[_]`.
- `Module Review Findings → llm4s-macros #2`: `.toString` parameter
  substitution — replaced by native interpolation.

Validation:

- `llm4sStructured/test`
- explicit all-module sweep (same gate as PR-15).

## Post-Roadmap Cleanup

These are not required for the 15-PR framework-parity pass, but they should be considered before publishing the native implementation as a real library.

### Remove Legacy LangChain4j Dependency Surface

Current root `build.sbt` still includes:

- `dev.langchain4j:langchain4j`
- `dev.langchain4j:langchain4j-open-ai`
- `dev.langchain4j:langchain4j-agentic`

Reason:

- Legacy demos and bridge code under `src/main/scala/org/l4j/template/l4j_macro` still reference LangChain4j.

Cleanup options:

- Move legacy code into a separate `legacy-langchain4j-demo` module.
- Rewrite demos to native `llm4s-*` modules.
- Remove LangChain4j dependencies after native demos cover the same flows.

### Add Published Module Metadata

Before publishing, each module should have:

- clear package docs
- stable public exports
- versioning policy
- binary compatibility expectations
- examples

### Add Integration Tests

Unit tests currently prove contracts and local behavior. Integration tests should be added later for:

- OpenAI-compatible live backend
- MCP stdio server fixture
- MCP HTTP server fixture
- pgvector-backed retrieval
- streaming tool-call deltas

### Add Documentation Examples

Recommended example docs:

- native AI service with prompt templates
- native tool calling
- structured output
- streaming
- session memory
- simple RAG
- advanced RAG
- deterministic workflow
- supervisor agent
- MCP tool provider
- guardrails
- multimodal request

## Module Review Findings

A full read-through of every module on the `claude_review` branch produced the
matrix below. Findings are ordered from the lowest-level dependency upward, so
each tier can be addressed without rework from the tier above.

Severity legend: **B**ug / **R**isk / **I**mprovement / **P**olish.

`Fixed` column: ISO date when the finding was addressed on this branch, or
`—` if still open (including findings that turned out to be false positives
or were deferred as design tradeoffs — see the commit history for details).

### Tier 0 — Foundation

#### `llm4s-core` (8 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `JsonSchema.scala:7-14` | `ObjectSchema.required` not validated against `properties` keys | Smart constructor enforcing `required ⊆ properties` | 2026-05-12 |
| 2 | R | `ChatProtocol.scala:48-51` | `ToolResult.StructuredJson → AiContent` flattens to text, loses JSON structure | Add `AiContent.Json` variant or carry raw JSON field | — |
| 3 | R | `ChatProtocol.scala:58` | `metadata: Map[String,String]` is lossy for structured values | Typed `AiMetadata` ADT | — |
| 4 | R | `ChatProtocol.scala:57` | `temperature` accepts any Double, no range check | Validate or document caller responsibility | 2026-05-12 |
| 5 | I | `Tools.scala:15-38` | `ToolResult` exposes `isError` but no error factories | Add `ToolResult.error(...)` helpers | — |
| 6 | I | `ModelMetadata.scala:3-12` | `Thinking` capability has no response-format support | Add `ThinkingOutput` capability + `ResponseFormat` block | — |
| 7 | I | `test/.../CoreTypesSpec.scala` | No negative tests for invalid temp / empty messages / circular schema | Property-based / boundary tests | — |

#### `llm4s-memory` (5 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `ChatMemory.scala:11-12` | Default `append` is non-atomic read-then-write (TOCTOU) | Override in `InMemoryChatMemory` with `Ref.updateAndGet` | 2026-05-12 |
| 2 | I | `MessageWindowMemory.scala:13-20` | Window trims on every op against full history (O(n)) | Trim only on read | — |
| 3 | I | `ChatMemory.scala:7-9` | No `clear(id)` operation in trait | Add `def clear(id: Id): F[Unit]` | — |
| 4 | R | `test/.../ChatMemorySpec.scala` | Tests are sync; race in default `append` is invisible | Add `parSequenceN` concurrent tests | — |
| 5 | P | `MemoryId.scala:3` | Wraps raw String, no validation surface | Document trust assumption | — |

### Tier 1 — Single-dep modules

#### `llm4s-rag` (14 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `EmbeddingVector.scala:6-16` | `cosineSimilarity` body ends on `require(...)` (Unit) — won't compile or returns wrong value | Move `require` outside computation; return dot/magnitude | — |
| 2 | B | `ContentAggregator.scala:13-14` | Dedup `maxBy(_.score)` drops metadata from losing duplicates | Merge metadata maps before selecting | 2026-05-12 |
| 3 | R | `InMemoryEmbeddingStore.scala:14-32` | Assumes unit-normalized vectors; no enforcement | Add `.normalize()` and apply in `add` | 2026-05-12 |
| 4 | R | `PgVectorEmbeddingStore.scala:36-37` | `IllegalArgumentException` thrown in constructor (not in `F`) | Move validation into factory returning `F[Either[...]]` | 2026-05-12 |
| 5 | R | `AdvancedContentRetriever.scala:6-11 vs 28-33` | Constructor vs `apply` param order disagree | Align signatures | — |
| 6 | I | `RetrievalAugmentor.scala:33-35` | `DefaultRetrievalAugmentor` replaces user message wholesale — loses images/files | Preserve original contents, append context as additional `AiContent.Text` | — |
| 7 | I | `AdvancedContentRetriever.scala:14-20` | No `minScore` cutoff before rerank | Add optional `minScore` filter | — |

#### `llm4s-agentic` (11 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `AgentScope.scala:15-18,23-33` | `value.asInstanceOf[A]` is unchecked; primitive class lookup can corrupt on mismatch | Return `F[Option[A]]` after `ClassTag` check | — |
| 2 | B | `SupervisorAgent.scala:34-36` | Default outputKey collides across nested supervisors | Require explicit key or namespace with step index/UUID | 2026-05-12 |
| 3 | R | `ParallelWorkflow.scala:12-13` | Both branches write to shared `AgentScope` without namespacing | Namespace keys (`left.*` / `right.*`) or document hazard | — |
| 4 | R | `SupervisorAgent.scala:22-24` | Single sub-agent failure aborts whole supervision | Per-step `Either` + aggregator decides fail-fast vs tolerate | — |
| 5 | R | `ConditionalWorkflow.scala:12-16` | Predicate side effects persist regardless of branch | Document mutation contract or evaluate lazily | — |
| 6 | I | `LoopWorkflow.scala:18-21` | `maxIterations` error doesn't cancel in-flight body | Fiber cancellation token | — |
| 7 | I | `test/.../AgenticWorkflowSpec.scala` | No parallel-scope-contention tests | Concurrent-write test cases | — |

### Tier 2 — Chat execution stack

#### `llm4s-runtime` (5 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `AiRuntime.scala:92-98` | `finishReason` ignored when no tool calls — `ContentFilter`/`Error` silently look like success | Raise typed error / log on Error/ContentFilter | 2026-05-12 |
| 2 | R | `AiRuntime.scala:85` | Off-by-one between bound (`turn >= maxTurns`) and message ("exceeded") | Align boundary or message | — |
| 3 | R | `ToolLoop.scala:49-50` | `escape` only handles `\` and `"`; newline/control-char in tool error breaks JSON | Use `ujson.Str(...)` for serialization | 2026-05-12 |
| 4 | I | `AiRuntime.scala:85-88` | Generic `RuntimeException` for loop limit | Typed `ChatLoopError` ADT | — |
| 5 | I | `AiRuntime.scala:104-106` | `InvocationContext.request` semantics unclear (pre-tool-call snapshot) | Document or pass updated messages | — |

#### `llm4s-streaming` (6 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `OpenAiStreamDecoder.scala:20` | `json("choices")(0)` crashes on empty array | `.arrOpt.flatMap(_.headOption)` | 2026-05-12 |
| 2 | R | `OpenAiStreamDecoder.scala:33-43` | Tool-call argument fragments emitted individually — no accumulation across frames | Accumulator state in decoder, or emit on completion | — |
| 3 | R | `OpenAiStreamDecoder.scala:39` | Missing `arguments` silently becomes `""` | Drop event or set `isPartial` flag | 2026-05-12 |
| 4 | R | `OpenAiStreamDecoder.scala:19-43` | `Completed` event has empty `AiMessage` — final text/tool-calls lost to late subscribers | Include accumulated text + tool calls in `Completed` | — |
| 5 | I | `StreamingAiRuntime.scala` | No streaming tool-loop variant | Add `StreamingToolLoopRuntime` or document | — |
| 6 | P | `TokenStream.scala:12-13` | Upstream stream errors aren't documented as observable via `collectText` | Document error propagation | — |

#### `llm4s-openai-compat` (10 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | B | `OpenAiWire.scala:53-56` | Redundant re-fetch of `"message"` from `choice` after `messageJson` already assigned | Read `tool_calls` from `messageJson` | 2026-05-12 |
| 2 | R | `OpenAiWire.scala:191-197` | `.str` access on `name`/`arguments` without guard — NPE on missing | `obj.get(...).collect { case ujson.Str(v) => v }` | 2026-05-12 |
| 3 | R | `SttpOpenAiTransport.scala:27-32` | All HTTP errors → `RuntimeException`; no retryable/auth/4xx distinction | Typed `HttpError` ADT (Retryable / Auth / Client / Server) | 2026-05-12 |
| 4 | R | `OpenAiStreamDecoder.scala:12` | Assumes one complete SSE event per `decodeLine` call — split frames break | Line-buffering transport wrapper | — |
| 5 | R | `OpenAiWire.scala:50-69` | No schema validation; missing `choices` crashes | Validate response shape up front | 2026-05-12 |
| 6 | I | `OpenAiCompatBackend.scala:14-25` | Doesn't validate `request.requiredCapabilities` against configured model | Capability config + early reject | — |
| 7 | P | `SttpOpenAiTransport.scala:28` | `ujson.read` errors are opaque | Wrap with context including (truncated) body | — |

### Tier 3 — Capabilities

#### `llm4s-tools` (4 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `ValueDecoder.scala:103,105` | Unchecked `asInstanceOf[ValueDecoder[Any]]` in derivation fold | Preserve type info or assert | — |
| 2 | R | `ValueDecoder.scala:88-89` | Enum decode uses `indexOf` then casts without checking for `-1` | Map lookup with explicit error | — |
| 3 | I | `ValueDecoder.scala:49-56` | Nested-array errors lose parent JSON path | Thread path context through recursion | — |
| 4 | I | `SchemaEncoder.scala:33-36` | Non-object products silently wrapped in `{"value": ...}` | Document or test the wrapping rule | — |
| 5 | P | `ToolDefinition.scala:57-58` | Error-JSON `escape` misses `\n\t\r`/control chars | Use `ujson.Str(...)` | — |

#### `llm4s-mcp` (8 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `StdioMcpTransport.scala:14-21` | Assumes strict request→response ordering — interleaved server responses break correlation | Correlate by `response.id`; queue out-of-order frames | 2026-05-12 |
| 2 | R | `McpClient.scala:27-30` | Malformed `argumentsJson` silently becomes `{}` | `raiseError` on parse failure | 2026-05-12 |
| 3 | R | `McpClient.scala:44-78` | Cascading `objOpt`/`arrOpt`/`strOpt` silently defaults missing fields | Validate mandatory fields explicitly | — |
| 4 | I | `McpSchemaConverter.scala:40-45` | Unknown MCP type → `StringSchema()` fallback | Return `Left(McpProtocolError)` | — |
| 5 | I | `HttpMcpTransport.scala:34` | Over-strong `Sync[F]` constraint | Downgrade to `MonadThrow[F]` | — |

#### `llm4s-guardrails` (8 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `GuardedToolKit.scala:30` | `recover` only catches `GuardrailBlockedException` — other errors bypass error wrapping | `handleErrorWith` and map non-guardrail errors uniformly | — |
| 2 | I | `RetryPolicy.scala:12-19` | Immediate retry, no backoff/jitter | `withBackoff(initial, factor, jitter)` builder | — |
| 3 | I | `GuardrailChain.scala:15-28` | Execution order is implicit (chain order) | Document or add priority field | — |
| 4 | P | `GuardedChatBackend.scala:12` | `retryPolicy` has default; usage doc shows explicit config — users may skip silently | Make required or add no-retry sentinel | — |

### Tier 4 — Composition

#### `llm4s-structured` (3 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `StructuredCodec.scala:26-27` | Catch-all `error.getMessage` may be null; loses context | Preserve cause + path; typed `StructuredDecodeError` | — |
| 2 | I | `StructuredOutputRuntime.scala:39-43, 72-76` | Refusal and malformed-JSON both → `RuntimeException` | Distinct `ModelRefusalError` vs `DecodeError` | — |
| 3 | I | `StructuredOutputRuntime.scala:22, 52` | No check that `toolKit` and structured response format are compatible | Validate or document interaction | — |
| 4 | P | `StructuredCodec.scala:10-27` | `schemaName` derived from class name — rename = silent schema break | Optional `schemaVersion`; doc compatibility rule | — |

#### `llm4s-macros` (3 files)

| # | Sev | Location | Issue | Fix | Fixed |
|---|-----|----------|-------|-----|-------|
| 1 | R | `AiService.scala:204` | Generated synchronous methods call `.unsafeRunSync()` — deadlock risk on calling thread | Generate `F[A]` returns by default; opt-in sync wrapper | — |
| 2 | R | `AiService.scala:193` | Parameter substitution uses `.toString` — `List(...)` etc. yield garbage prompts | `@paramFormat` annotation or JSON serialization default | — |
| 3 | I | `AiService.scala:212-250` | Missing `StructuredCodec`/`Reader` summon error doesn't guide user | Improve error message with derivation hint | — |
| 4 | I | `AiService.scala:158` | Structured return types not verified at expansion time | Use `Expr.summon` to fail early | — |
| 5 | I | `AiService.scala:144-151` | Placeholder name validation OK, but no warning when interpolating non-stringy types | Compile-time advisory or formatter typeclass | — |
| 6 | P | `AiService.scala:70` | Overloads (with/without memory, with/without guardrails) lack disambiguation guidance | Inline scaladoc / `@deprecated` redirect | — |

### Cross-Cutting Themes

1. **Unsafe JSON parsing path.** `OpenAiWire`, `OpenAiStreamDecoder`, and
   `McpClient` all use direct `apply`/`.str` access; one missing field crashes
   the chat path. A small typed-extraction helper would close this everywhere.
2. **Error type erasure.** Backend transport, structured decoding, runtime
   loop limits, guardrails, and macros all collapse to `RuntimeException`. A
   small hierarchy of typed errors per module would improve operability.
3. **Concurrency invariants undocumented.** `ChatMemory.append`, `AgentScope`,
   `ParallelWorkflow`, and `StdioMcpTransport` all have race conditions that
   pass tests today because the tests are sequential.
4. **SSE / streaming completeness gaps.** Split frames, tool-call argument
   accumulation, and `Completed` event payload are all incomplete; a streaming
   consumer cannot reliably reconstruct the final message.
5. **Capability validation never runs.** `ModelCapabilities` exists in core
   but `OpenAiCompatBackend` does not consult it; vision/file/streaming/tool
   requests hit unsupported models silently.

### Suggested Fix Order (Highest Leverage First)

1. `EmbeddingVector.cosineSimilarity` bug — core RAG primitive is broken.
   (Re-examined 2026-05-12: false positive; Scala 3 indented method body
   already extends past the `require` to the final `if/else`.)
2. `OpenAiWire.scala:53` redundant lookup + `OpenAiWire:191-197` unsafe `.str`
   — production chat path. (Fixed 2026-05-12.)
3. `AiRuntime.scala:92-98` finish-reason handling — silently swallows
   content-filter / error completions. (Fixed 2026-05-12.)
4. `AiService.scala:204` `unsafeRunSync` — deadlock surface in macro-generated
   code.
5. `ChatMemory.append` atomicity — multi-user correctness.
   (Fixed 2026-05-12.)
6. SSE decoder fixes: split-frame buffering, tool-arg accumulation,
   `Completed` payload. (Partial 2026-05-12: empty-choices/empty-delta guards
   landed; split-frame buffering, tool-arg accumulation, and richer
   `Completed` payload still open.)
7. Typed `HttpError` ADT in `SttpOpenAiTransport` — unlocks real retry
   policies. (Fixed 2026-05-12.)

## Current Status Summary

Completed:

- PR-1 through PR-15
- Framework-parity roadmap implementation is complete.

In progress:

- PR-16: non-macro `AiAgent` ergonomics layer on `pr16_non_macro_aiagent`.

Remaining:

- No PRs remain in the original framework-parity roadmap.
- Hardening backlog tracked in `Module Review Findings` above.

Immediate next step:

- Land PR-16 (non-macro `AiAgent` + parallel demo).
- Optional post-roadmap cleanup: remove or isolate the legacy LangChain4j demo/dependency surface before publishing.
- Address top items from `Suggested Fix Order` in `Module Review Findings`, starting with the broken `EmbeddingVector.cosineSimilarity`.
