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

## Current Status Summary

Completed:

- PR-1 through PR-15
- Framework-parity roadmap implementation is complete.

Remaining:

- No PRs remain in the original framework-parity roadmap.

Immediate next step:

- Optional post-roadmap cleanup: remove or isolate the legacy LangChain4j demo/dependency surface before publishing.
