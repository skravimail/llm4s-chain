# LCEL-Style DSL Draft Plan

Status: draft only

Branch: `llm4s-lcel`

This document sketches a possible LCEL-style composition DSL for this repository. It is intentionally a design and planning artifact only. No implementation is included in this branch beyond this draft plan.

## Goal

Add a typed, Scala-native composition layer that makes it easy to express chains such as:

- prompt template -> model -> parser
- input -> retriever -> prompt enrichment -> model
- input -> parallel branches -> merge
- model call -> structured output decoder

The target is LCEL-like ergonomics without importing LangChain or weakening the current typed module boundaries.

## Non-Goals

- Do not replace `AiRuntime`, `AiAgent`, `WorkflowAgent`, or RAG primitives.
- Do not add dynamic Python-style operator magic at the expense of type clarity.
- Do not implement provider-specific orchestration in the DSL core.
- Do not mix streaming and non-streaming execution in the first cut unless the abstraction remains clean.

## Existing Reusable Pieces

The repo already has most of the execution building blocks:

- `llm4s-agentic` provides typed workflow composition primitives.
- `llm4s-structured` provides LLM invocation, typed decoding, tools, and memory-aware calls.
- `llm4s-rag` provides retrieval and augmentation stages.
- `llm4s-streaming` provides stream event handling.
- `llm4s-runtime` already owns the tool loop and request execution semantics.

What is missing is a single universal composition protocol that can wrap all of them.

## Proposed Core Abstraction

Introduce a new module, likely `llm4s-dsl` or `llm4s-runnable`, centered on a single typed abstraction:

```scala
trait Runnable[F[_], -In, +Out]:
  def run(input: In, ctx: RunContext[F]): F[Out]
```

Supporting context:

```scala
trait RunContext[F[_]]
```

The first version of `RunContext` should stay small and only hold what composition actually needs. The recommended direction is to keep it as a pure capability carrier, not a mutable scratchpad. It should carry execution services such as tracing, runtime config, backend access, listeners, and policy handles, while pipeline data continues to flow explicitly through `In => Out`.

If shared intermediate state is needed later, add it as a separate typed facility rather than overloading `RunContext`. A split like `RunContext[F]` plus `RunState[F]` or `Scratchpad[F]` keeps the core model easier to reason about and avoids turning context into a generic bag of hidden mutable values.

Illustrative shape:

```scala
trait RunContext[F[_]]:
  def backend: ChatBackend[F]
  def config: RuntimeConfig
  def listener: RuntimeListener[F]

trait RunState[F[_]]:
  def put[A](key: Key[A], value: A): F[Unit]
  def get[A](key: Key[A]): F[Option[A]]
```

## Proposed First-Class Nodes

The DSL becomes useful only if the graph nodes are small and composable. The initial set should cover:

- `Runnable.fromFunction`
- prompt/template node
- `AiAgent` adapter
- `WorkflowAgent` adapter
- retriever adapter
- retrieval augmentor adapter
- structured decoder/parser node
- merge/map/assign nodes

## Proposed Combinators

Minimum useful operator set:

- `andThen` or `>>>`: sequential composition
- `map`
- `contramap`
- `zip`
- `par`
- `flatMap` only if the resulting semantics stay understandable
- `named`: attach graph/debug names

Avoid overloading too many symbolic operators in the first cut. Readability matters more than surface cleverness.

## Likely API Shape

Illustrative only:

```scala
val chain =
  PromptTemplate("Answer this: {question}") >>>
  ChatModel(openAiBackend) >>>
  StringOutput

val ragChain =
  Input.pick("question") >>>
  retriever >>>
  Assign("sources") >>>
  promptBuilder >>>
  chatModel >>>
  summaryParser
```

This should compile into ordinary typed Scala values, not interpreted strings.

## Adaptation Strategy

Adapters should be added rather than rewriting existing modules:

- `AiAgent[F]` stays as-is and gets a `toRunnable` adapter.
- `WorkflowAgent[F, In, Out]` stays canonical in `llm4s-agentic`.
- `ContentRetriever[F]` becomes a runnable stage from query to sources.
- `RetrievalAugmentor[F]` becomes a runnable stage from request to augmented request.

That keeps the current API stable while allowing a new composition surface to emerge beside it.

## Phased Plan

### Phase 1: Core DSL Skeleton

- create the new module
- add `Runnable[F, In, Out]`
- add `RunContext[F]`
- keep `RunContext` capability-only in the initial cut
- add basic sequential and parallel combinators
- add unit tests for composition laws and type-safe chaining

### Phase 2: LLM and Parser Adapters

- add `AiAgent` adapter
- add `StructuredCodec`-based parser/output adapters
- add prompt/template nodes
- add examples for prompt -> model -> typed decode

### Phase 3: RAG Adapters

- add `ContentRetriever` adapter
- add `RetrievalAugmentor` adapter
- add examples for query -> retrieve -> augment -> chat
- keep the new `RetrievalQuery` surface compatible with DSL-based retrieval flows

### Phase 4: Workflow Interop

- add `WorkflowAgent` and `Workflow` adapters
- define how `AgentScope` and capability-only `RunContext` interact
- avoid duplicating orchestration semantics already present in `llm4s-agentic`

### Phase 5: Observability and Introspection

- attach names/labels to nodes
- render a graph view or Mermaid output
- thread `RuntimeListener` / workflow listener hooks through the DSL

### Phase 6: Streaming Review

- decide whether streaming should use:
  - a separate `StreamingRunnable`
  - or a polymorphic output model
- do this only after the non-streaming DSL is stable

## Open Design Questions

- If a scratch store is needed, should it be a separate typed `RunState` or a narrower scoped facility?
- Should structured output parsing be a normal terminal node or part of the model node?
- Should prompt templates operate on `Map[String, Any]`, typed input records, or both?
- How much symbolic syntax is desirable in Scala 3 before readability suffers?
- Should parallel composition fail fast or collect typed branch failures?
- Is streaming a sibling abstraction or part of the same one?

## Must-Resolve Before Implementation

The following design decisions should be locked before phases 1-5 begin. If these drift mid-implementation, the DSL will likely grow overlapping abstractions or inconsistent composition semantics.

### 1. Canonical `Runnable` Shape

Decide whether the base abstraction is strictly:

```scala
trait Runnable[F[_], -In, +Out]:
  def run(input: In, ctx: RunContext[F]): F[Out]
```

or whether naming, metadata, or introspection hooks are part of the base trait. The default recommendation is to keep the base trait minimal and layer naming/introspection on top.

### 2. Explicit Typed Dataflow vs Dynamic Value Bag

Decide whether the DSL is fundamentally:

- explicit typed `In => Out` composition
- or a more dynamic context/value-map model

The recommended direction is explicit typed dataflow. A dynamic bag-of-values model would be more flexible at first, but it would weaken one of the repo's main strengths: type-directed composition.

### 3. `RunContext` Boundary

Keep `RunContext` capability-only and do not let it turn into a hidden mutable scratchpad. If shared intermediate state becomes necessary, it should be modeled as a separate typed facility.

### 4. Parallel Failure And Cancellation Semantics

Define what `par` means when one branch fails:

- fail fast and cancel siblings
- wait for all branches
- collect typed branch failures

This should be decided early because it affects laws, user expectations, and interop with existing `cats.Parallel` behavior.

### 5. Model Node Input/Output Shape

Decide what the canonical model-facing node consumes and emits:

- `String`
- prompt/template value
- `ChatRequest`
- `ChatResponse`
- plain assistant text

The cleanest design is usually to keep model invocation close to the existing `ChatRequest` / response model internally, while offering thinner convenience wrappers on top.

### 6. Structured Parsing Placement

Decide whether structured output parsing is:

- a separate terminal node
- or part of a model wrapper

The recommended direction is a separate node so plain text generation and typed decoding stay orthogonal.

### 7. Workflow Interop Boundary

Decide whether:

- the DSL compiles down to `Workflow`
- `Workflow` is simply one adapter into the DSL
- or both abstractions coexist with distinct responsibilities

This matters because `llm4s-agentic` already has orchestration semantics, `AgentScope`, and listener behavior. The DSL should not accidentally create a second orchestration model with unclear ownership.

### 8. RAG Composition Contract

Decide how retrieval outputs move through the chain:

- strongly typed values such as `List[RetrievedSource]`
- explicit prompt-builder nodes
- optional assignment/merge nodes

This should be resolved before phase 3 so RAG support does not push the DSL toward an untyped scratch-map design.

## Risks

- Overlapping too much with `Workflow` could create two orchestration systems with unclear boundaries.
- A too-dynamic value model would weaken the type safety that is currently a strength of the repo.
- A too-abstract context object could become a dumping ground for unrelated state; this is why `RunContext` should start as capabilities only.
- Streaming can distort the design if included too early.

## Recommended First Deliverable

The first implementation should be deliberately narrow:

1. new `llm4s-dsl` module
2. `Runnable[F, In, Out]`
3. capability-only `RunContext[F]`
4. sequential + parallel composition
5. `AiAgent` adapter
6. prompt/template node
7. typed parser node
8. one end-to-end example in docs/tests

If that works cleanly, RAG and workflow interop should come next.

## Acceptance Criteria For A Future Implementation

- A simple prompt -> model -> parser chain is expressible as a single typed value.
- A parallel fanout + merge flow is expressible without custom orchestration code.
- Existing `AiAgent`, `WorkflowAgent`, and RAG components can be adapted without invasive rewrites.
- The new layer improves usability without obscuring runtime semantics or errors.
- The design is documented in `docs/USAGE.md` only after the API stabilizes.
