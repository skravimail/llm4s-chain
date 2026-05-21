# Graph Report - .  (2026-05-18)

## Corpus Check
- 20 files · ~56,088 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1022 nodes · 1155 edges · 84 communities (51 shown, 33 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 72 edges (avg confidence: 0.81)
- Token cost: 23,130 input · 2,544 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Runtime Listener & Streaming|Runtime Listener & Streaming]]
- [[_COMMUNITY_Sequential Story Demo|Sequential Story Demo]]
- [[_COMMUNITY_MCP Client & Transport|MCP Client & Transport]]
- [[_COMMUNITY_RAG Embeddings & Retrieval|RAG Embeddings & Retrieval]]
- [[_COMMUNITY_LCEL DSL Adapters|LCEL DSL Adapters]]
- [[_COMMUNITY_Parallel Review Demo|Parallel Review Demo]]
- [[_COMMUNITY_Chat Message Domain|Chat Message Domain]]
- [[_COMMUNITY_AGENTS.md Doc Concepts|AGENTS.md Doc Concepts]]
- [[_COMMUNITY_LCEL Draft Plan (doc)|LCEL Draft Plan (doc)]]
- [[_COMMUNITY_Memory-Aware Runtime|Memory-Aware Runtime]]
- [[_COMMUNITY_Changelog (doc)|Changelog (doc)]]
- [[_COMMUNITY_HTTP Listener & Natchez|HTTP Listener & Natchez]]
- [[_COMMUNITY_ChatBackend Laws|ChatBackend Laws]]
- [[_COMMUNITY_CODE_REVIEW (doc)|CODE_REVIEW (doc)]]
- [[_COMMUNITY_ToolKit & Tool Definitions|ToolKit & Tool Definitions]]
- [[_COMMUNITY_Content & Tool Wire Types|Content & Tool Wire Types]]
- [[_COMMUNITY_Guardrail Results & Tags|Guardrail Results & Tags]]
- [[_COMMUNITY_Natchez Adapter Tests|Natchez Adapter Tests]]
- [[_COMMUNITY_OpenAI-Compat Backend|OpenAI-Compat Backend]]
- [[_COMMUNITY_Module Overview (doc)|Module Overview (doc)]]
- [[_COMMUNITY_Model Capabilities & Usage|Model Capabilities & Usage]]
- [[_COMMUNITY_Runnable Combinators|Runnable Combinators]]
- [[_COMMUNITY_Guardrail Chain|Guardrail Chain]]
- [[_COMMUNITY_App Config Loading|App Config Loading]]
- [[_COMMUNITY_Prompt Template & Retriever|Prompt Template & Retriever]]
- [[_COMMUNITY_Guardrail Listener|Guardrail Listener]]
- [[_COMMUNITY_Workflow Agents|Workflow Agents]]
- [[_COMMUNITY_Prior Review Findings (doc)|Prior Review Findings (doc)]]
- [[_COMMUNITY_Workflow Listener|Workflow Listener]]
- [[_COMMUNITY_JSON Schema Types|JSON Schema Types]]
- [[_COMMUNITY_Value Decoders|Value Decoders]]
- [[_COMMUNITY_Supervisor & Planner|Supervisor & Planner]]
- [[_COMMUNITY_Trace Context IDs|Trace Context IDs]]
- [[_COMMUNITY_Agent Demo Main|Agent Demo Main]]
- [[_COMMUNITY_AHC Streaming Backend|AHC Streaming Backend]]
- [[_COMMUNITY_Schema Encoder Derivation|Schema Encoder Derivation]]
- [[_COMMUNITY_AiAgent Spec|AiAgent Spec]]
- [[_COMMUNITY_AiRuntime Errors|AiRuntime Errors]]
- [[_COMMUNITY_Agent Scope|Agent Scope]]
- [[_COMMUNITY_Chat Transcript|Chat Transcript]]
- [[_COMMUNITY_Stream Event Types|Stream Event Types]]
- [[_COMMUNITY_AiRuntime Spec|AiRuntime Spec]]
- [[_COMMUNITY_BSP Sbt Config|BSP Sbt Config]]
- [[_COMMUNITY_In-Memory Chat Memory|In-Memory Chat Memory]]
- [[_COMMUNITY_Message Window Memory|Message Window Memory]]
- [[_COMMUNITY_AiContent Variants|AiContent Variants]]
- [[_COMMUNITY_Guarded Tool Executor|Guarded Tool Executor]]
- [[_COMMUNITY_Embedding Vector Math|Embedding Vector Math]]
- [[_COMMUNITY_README (doc)|README (doc)]]
- [[_COMMUNITY_Tool Def Model|Tool Def Model]]
- [[_COMMUNITY_Loop Workflow|Loop Workflow]]
- [[_COMMUNITY_SubAgent Registry|SubAgent Registry]]
- [[_COMMUNITY_Docs Layout Spec|Docs Layout Spec]]
- [[_COMMUNITY_Response Format|Response Format]]
- [[_COMMUNITY_Memory Runtime Spec|Memory Runtime Spec]]
- [[_COMMUNITY_Query Transformer|Query Transformer]]
- [[_COMMUNITY_ReRanker|ReRanker]]
- [[_COMMUNITY_Conditional Workflow|Conditional Workflow]]
- [[_COMMUNITY_Workflow Errors|Workflow Errors]]
- [[_COMMUNITY_Permissions Settings|Permissions Settings]]
- [[_COMMUNITY_Codex Hooks Config|Codex Hooks Config]]
- [[_COMMUNITY_Runnable Spec Support|Runnable Spec Support]]
- [[_COMMUNITY_Text Output Adapter|Text Output Adapter]]
- [[_COMMUNITY_Chat Options|Chat Options]]
- [[_COMMUNITY_MCP Schema Converter|MCP Schema Converter]]
- [[_COMMUNITY_OpenAI Backend Laws Spec|OpenAI Backend Laws Spec]]
- [[_COMMUNITY_Build Dependencies|Build Dependencies]]
- [[_COMMUNITY_Query Router|Query Router]]
- [[_COMMUNITY_AppConfig Spec|AppConfig Spec]]
- [[_COMMUNITY_Structured Codec|Structured Codec]]
- [[_COMMUNITY_AGENTS.md (doc index)|AGENTS.md (doc index)]]
- [[_COMMUNITY_Core Types Spec|Core Types Spec]]
- [[_COMMUNITY_Guarded Chat Backend|Guarded Chat Backend]]
- [[_COMMUNITY_Memory ID|Memory ID]]
- [[_COMMUNITY_JSON Schema Wire Roundtrip|JSON Schema Wire Roundtrip]]
- [[_COMMUNITY_WorkflowAgent Rename Spec|WorkflowAgent Rename Spec]]

## God Nodes (most connected - your core abstractions)
1. `Runnable` - 20 edges
2. `llm4s-template — Code Review` - 19 edges
3. `Completed PRs` - 17 edges
4. `Native Scala 3 Usage Guide` - 17 edges
5. `GuardrailChain` - 17 edges
6. `RuntimeListener` - 17 edges
7. `TracedSttpBackend` - 17 edges
8. `NatchezRuntimeListener` - 16 edges
9. `TracingWiring` - 16 edges
10. `LCEL-Style DSL Draft Plan` - 15 edges

## Surprising Connections (you probably didn't know these)
- `Runnable` --implements--> `Runnable`  [INFERRED]
  llm4s-dsl/src/main/scala/org/l4j/template/llm4s/dsl/Runnable.scala → LCEL_DRAFT_PLAN.md
- `RunContext` --implements--> `RunContext`  [INFERRED]
  llm4s-dsl/src/main/scala/org/l4j/template/llm4s/dsl/RunContext.scala → LCEL_DRAFT_PLAN.md
- `retrieve()` --calls--> `RetrievedSource`  [INFERRED]
  llm4s-dsl/src/test/scala/org/l4j/template/llm4s/dsl/RagDslSpec.scala → llm4s-rag/src/main/scala/org/l4j/template/llm4s/rag/RetrievedSource.scala
- `retrieve()` --calls--> `RetrievedSource`  [INFERRED]
  src/main/scala/org/l4j/template/demo/LCEL_DEMO.scala → llm4s-rag/src/main/scala/org/l4j/template/llm4s/rag/RetrievedSource.scala
- `retrieve()` --calls--> `RetrievedSource`  [INFERRED]
  src/main/scala/org/l4j/template/demo/LCEL_OPERATOR_DEMO.scala → llm4s-rag/src/main/scala/org/l4j/template/llm4s/rag/RetrievedSource.scala

## Communities (84 total, 33 thin omitted)

### Community 0 - "Runtime Listener & Streaming"
Cohesion: 0.05
Nodes (20): RuntimeListener, Default, Completed, Event, Failed, ProviderReq, RecordingBackend, Started (+12 more)

### Community 1 - "Sequential Story Demo"
Cohesion: 0.06
Nodes (13): SequentialDemoApi, StoryDraft, SequentialDemoMain, AiRuntime, ChatModel, InvocationContext, TraceDemoMain, chat() (+5 more)

### Community 2 - "MCP Client & Transport"
Cohesion: 0.06
Nodes (16): ToolResult, HttpMcpTransport, McpClient, map(), McpClientSpec, RecordingTransport, File, Image (+8 more)

### Community 3 - "RAG Embeddings & Retrieval"
Cohesion: 0.07
Nodes (14): EmbeddingContentRetriever, PgVectorEmbeddingStore, search(), InMemoryEmbeddingStore, CompiledFilter, PgVectorConfig, MetadataRanker, RagSpec (+6 more)

### Community 4 - "LCEL DSL Adapters"
Cohesion: 0.06
Nodes (16): AiAgentRunnable, Assigned, ContentRetrieverRunnable, GeminiDefineArgs, GeminiFlashTraceLcelDemoMain, WikiLookup, Binary, Leaf (+8 more)

### Community 5 - "Parallel Review Demo"
Cohesion: 0.07
Nodes (13): CvScoredReview, CvUnderReview, ParallelDemoApi, ParallelWorkflow, AppConfig, BackendSupport, ListenerBundle, OpenAiCompatConfig (+5 more)

### Community 6 - "Chat Message Domain"
Cohesion: 0.07
Nodes (20): AiMessage, ChatMessage, ChatRequest, ChatResponse, SystemMessage, text(), ToolResultMessage, UserMessage (+12 more)

### Community 7 - "AGENTS.md Doc Concepts"
Cohesion: 0.08
Nodes (30): AgentWorkflow, `AiAgent` Builder, Architecture, Capability Checks, code:mermaid (flowchart TB), code:scala (val scopedResults =), code:scala (import org.l4j.template.llm4s.rag.AdvancedContentRetriever), code:scala (val parallel = ParallelWorkflow(writer.workflow, editor.work) (+22 more)

### Community 8 - "LCEL Draft Plan (doc)"
Cohesion: 0.06
Nodes (32): 1. Canonical `Runnable` Shape, 2. Explicit Typed Dataflow vs Dynamic Value Bag, 3. `RunContext` Boundary, 4. Parallel Failure And Cancellation Semantics, 5. Model Node Input/Output Shape, 6. Structured Parsing Placement, 7. Workflow Interop Boundary, 8. RAG Composition Contract (+24 more)

### Community 9 - "Memory-Aware Runtime"
Cohesion: 0.09
Nodes (8): AiAgent, ChatMemory, JsonExtractor, MemoryAwareRuntime, Review, StructuredOutputRuntime, RecordingBackend, Review

### Community 10 - "Changelog (doc)"
Cohesion: 0.06
Nodes (30): 2026-05-17 — CODE_REVIEW.md punch list cleared (PR-1 through PR-17), Add Documentation Examples, Add Integration Tests, Add Published Module Metadata, code:bash (env SBT_OPTS=-Dsbt.boot.directory=/Users/alpha/AI_ML/llm4s-t), code:bash (sbt 'llm4sMcp/test'), Completed PRs, Current Status Summary (+22 more)

### Community 11 - "HTTP Listener & Natchez"
Cohesion: 0.07
Nodes (8): HttpListener, NatchezHttpListener, Default, Event, Fail, Req, Resp, TracedSttpBackend

### Community 12 - "ChatBackend Laws"
Cohesion: 0.09
Nodes (8): ChatBackend, LawFailure, DefaultRunContext, StreamingChatModel, RecordingStreamingBackend, RunContext, RunContext, StreamingRunnable

### Community 13 - "CODE_REVIEW (doc)"
Cohesion: 0.08
Nodes (24): 10. JSON is hand‑built with string interpolation in at least one place, 11. `dropLeadingSystem` is a hack that hides a real modelling problem, 12. `AiAgent` constructs a fresh `AiRuntime` per agent and per copy, 13. `AiAgent` surface is asymmetric and cramped, 14. Module boundaries leak, 15. Build hygiene, 16. Documentation is split awkwardly, 17. Naming nits that will compound (+16 more)

### Community 14 - "ToolKit & Tool Definitions"
Cohesion: 0.10
Nodes (6): ToolEntry, ToolDefinition, ToolKit, Filters, Greet, SearchArgs

### Community 15 - "Content & Tool Wire Types"
Cohesion: 0.10
Nodes (10): Content, StructuredJson, Text, ToolCall, ToolSchema, McpToolExecutor, McpToolProvider, RecordingBackend (+2 more)

### Community 16 - "Guardrail Results & Tags"
Cohesion: 0.11
Nodes (16): Allow, Block, GuardrailResult, GuardrailViolation, AppendTag, BlockedUntilReleased, BlockTool, BlockToolWith (+8 more)

### Community 17 - "Natchez Adapter Tests"
Cohesion: 0.09
Nodes (3): NatchezAdapterSpec, NatchezRuntimeListener, NatchezWorkflowListener

### Community 18 - "OpenAI-Compat Backend"
Cohesion: 0.13
Nodes (9): OpenAiCompatBackend, RecordingTransport, Client, Forbidden, OpenAiHttpError, RateLimited, Server, Unauthorized (+1 more)

### Community 19 - "Module Overview (doc)"
Cohesion: 0.10
Nodes (20): Cross-Cutting Themes, `llm4s-agentic` (11 files), `llm4s-core` (8 files), `llm4s-guardrails` (8 files), `llm4s-macros` (removed in PR-16 — historical only), `llm4s-mcp` (8 files), `llm4s-memory` (5 files), `llm4s-openai-compat` (10 files) (+12 more)

### Community 20 - "Model Capabilities & Usage"
Cohesion: 0.14
Nodes (4): ModelCapabilities, UnsupportedModelCapabilities, Usage, OpenAiWire

### Community 21 - "Runnable Combinators"
Cohesion: 0.15
Nodes (12): |(), andThen(), map(), merge(), named(), NamedRunnable, par(), run() (+4 more)

### Community 23 - "App Config Loading"
Cohesion: 0.21
Nodes (6): AppConfig, LlmConfig, LoggingConfig, Provider, TraceLevel, TracingConfig

### Community 24 - "Prompt Template & Retriever"
Cohesion: 0.17
Nodes (5): AdvancedContentRetriever, PromptTemplate, AugmentedChatRequest, DefaultRetrievalAugmentor, RetrievalAugmentor

### Community 25 - "Guardrail Listener"
Cohesion: 0.13
Nodes (7): Default, GuardrailListener, Event, InputBlocked, OutputBlocked, ToolBlocked, NatchezGuardrailListener

### Community 26 - "Workflow Agents"
Cohesion: 0.15
Nodes (4): WorkflowAgent, SequenceWorkflow, andThen(), WorkflowRunnable

### Community 27 - "Prior Review Findings (doc)"
Cohesion: 0.13
Nodes (14): 1. High: the default parallel guardrail path silently drops non-blocking transformations, 2. High: `StdioMcpTransport` is not safe under the runtime's parallel tool execution model, 3. Medium: `HttpMcpTransport` generates JSON-RPC ids but never validates the response id, 4. Medium: CI is miswired for this Scala 3 multi-project build, 5. Medium: the streaming OpenAI-compatible surface is only partially implemented but documented as complete, 6. Medium: no-auth providers still receive an `Authorization` header, 7. `LoopWorkflow` still uses direct recursion and a generic `RuntimeException`, 8. `docs/USAGE.md` has stale and non-compiling examples (+6 more)

### Community 28 - "Workflow Listener"
Cohesion: 0.19
Nodes (7): Default, instrumented(), WorkflowListener, Event, Failed, Started, Succeeded

### Community 29 - "JSON Schema Types"
Cohesion: 0.17
Nodes (10): AnyOfSchema, ArraySchema, BooleanSchema, EnumSchema, IntegerSchema, JsonSchema, NumberSchema, ObjectSchema (+2 more)

### Community 30 - "Value Decoders"
Cohesion: 0.24
Nodes (4): decode(), EnumValueDecoder, ProductValueDecoder, ValueDecoder

### Community 31 - "Supervisor & Planner"
Cohesion: 0.22
Nodes (5): PlanStep, StepResult, SupervisorAgent, aggregate(), ContentAggregator

### Community 32 - "Trace Context IDs"
Cohesion: 0.33
Nodes (3): SpanId, TraceContext, TraceId

### Community 33 - "Agent Demo Main"
Cohesion: 0.29
Nodes (4): AgentDemoMain, CvReview, DefineArgs, WikiLookup

### Community 34 - "AHC Streaming Backend"
Cohesion: 0.24
Nodes (3): AhcOpenAiStreamingTransport, OpenAiCompatStreamingBackend, RecordingTransport

### Community 35 - "Schema Encoder Derivation"
Cohesion: 0.20
Nodes (3): EnumSchemaEncoder, ProductSchemaEncoder, SchemaEncoder

### Community 36 - "AiAgent Spec"
Cohesion: 0.22
Nodes (3): AiAgentSpec, RecordingBackend, Review

### Community 37 - "AiRuntime Errors"
Cohesion: 0.25
Nodes (6): AiRuntimeError, ContentFiltered, MaxTurnsExceeded, ProviderError, ToolFailed, ToolMissing

### Community 40 - "Stream Event Types"
Cohesion: 0.29
Nodes (6): Completed, StreamEvent, TextDelta, ThinkingDelta, ToolCallCompleted, ToolCallDelta

### Community 42 - "BSP Sbt Config"
Cohesion: 0.33
Nodes (5): argv, bspVersion, languages, name, version

### Community 45 - "AiContent Variants"
Cohesion: 0.40
Nodes (4): AiContent, File, Image, Text

### Community 48 - "README (doc)"
Cohesion: 0.40
Nodes (4): code:bash (export OMLX_API_KEY=local-dev-placeholder), code:bash (# 1. AiAgent end-to-end: plain chat, typed CvReview return, ), code:bash (sbt scalafmtAll), code:block4 (llm4s-core/             provider-neutral protocol ADTs)

### Community 53 - "Response Format"
Cohesion: 0.50
Nodes (3): JsonSchema, ResponseFormat, Text

### Community 58 - "Workflow Errors"
Cohesion: 0.67
Nodes (3): MaxIterationsExceeded, MissingSubAgent, WorkflowError

## Knowledge Gaps
- **220 isolated node(s):** `McpClientSpec`, `McpContent`, `Text`, `Image`, `File` (+215 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `RuntimeConfig` connect `Sequential Story Demo` to `Agent Demo Main`, `LCEL DSL Adapters`, `Parallel Review Demo`, `Chat Message Domain`, `Memory-Aware Runtime`, `ChatBackend Laws`?**
  _High betweenness centrality (0.136) - this node is a cross-community bridge._
- **Why does `ChatResponse` connect `Chat Message Domain` to `Guardrail Results & Tags`, `Sequential Story Demo`, `Model Capabilities & Usage`, `Content & Tool Wire Types`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **Why does `ChatModel` connect `Sequential Story Demo` to `LCEL DSL Adapters`, `ChatBackend Laws`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `Runnable` (e.g. with `Runnable` and `ChatModel`) actually correct?**
  _`Runnable` has 6 INFERRED edges - model-reasoned connections that need verification._
- **What connects `McpClientSpec`, `McpContent`, `Text` to the rest of the system?**
  _220 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Runtime Listener & Streaming` be split into smaller, more focused modules?**
  _Cohesion score 0.04717853839037928 - nodes in this community are weakly interconnected._
- **Should `Sequential Story Demo` be split into smaller, more focused modules?**
  _Cohesion score 0.05919661733615222 - nodes in this community are weakly interconnected._