# LCEL-Style DSL Draft Plan

Status: draft plan plus initial scaffold for the first six core design decisions

Branch: `llm4s-lcel`

This document sketches a possible LCEL-style composition DSL for this repository. The branch now includes an initial `llm4s-dsl` scaffold that implements the first six core design decisions, while leaving workflow interop, RAG interop, and streaming for later phases.

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
trait Runnable[F[_], In, Out]:
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

## Core Decisions Implemented

The first six design decisions are now fixed in the initial scaffold. They are documented here so later phases build on the same assumptions instead of reopening foundational questions.

### 1. Canonical `Runnable` Shape

Decision:

```scala
trait Runnable[F[_], In, Out]:
  def run(input: In, ctx: RunContext[F]): F[Out]
```

Why:

- keeps the base trait small and unsurprising
- makes composition easy to test
- avoids baking graph metadata into the execution contract
- leaves room to layer naming/introspection on top, which the scaffold does through a `named(...)` wrapper rather than trait-level metadata

Note:

- The public API is now variant in `In` and `Out`, but the implementation uses a small hidden `RunValue` wrapper internally rather than exposing raw `F[Out]` directly on the base trait. That keeps the public composition surface expressive while staying within Scala 3's variance rules for invariant effect types.

### 2. Explicit Typed Dataflow vs Dynamic Value Bag

Decision:

- use explicit typed `In => Out` composition
- do not make context a dynamic bag of values

Why:

- aligns with the repo's existing strengths around typed composition
- keeps intermediate values visible in the type signature
- avoids hidden dependencies between nodes
- makes refactors and local reasoning easier than a stringly scratch-map model

### 3. `RunContext` Boundary

Decision:

- keep `RunContext` capability-only
- do not treat it as mutable shared pipeline state
- if shared state becomes necessary later, model it as a separate typed facility

Why:

- prevents context from becoming an unstructured junk drawer
- keeps pipeline data flowing through the graph, not through hidden side channels
- preserves testability and determinism
- keeps future stateful features optional rather than infecting every node from day one

### 4. Parallel Failure And Cancellation Semantics

Decision:

- `par` should fail fast and cancel sibling branches

Why:

- matches user expectations from `cats.Parallel` and `IO.parTupled`
- avoids leaving useless work running after one branch has already made the whole result invalid
- keeps the implementation simple and efficient in the first cut

The new tests explicitly lock this in by verifying sibling cancellation on failure.

### 5. Model Node Input/Output Shape

Decision:

- keep the canonical model-facing node at `ChatRequest => ChatResponse`
- provide thinner helper nodes around it, such as prompt builders and text extractors

Why:

- reuses the repo's existing provider-neutral protocol instead of inventing a second one
- keeps model invocation close to the current backend contract
- makes advanced features like response formats, tools, and metadata naturally expressible
- allows ergonomics to come from surrounding nodes instead of shrinking the core protocol too early

### 6. Structured Parsing Placement

Decision:

- keep structured parsing as a separate terminal node

Why:

- keeps plain text generation and typed decoding orthogonal
- allows the same model node to support both unstructured and structured flows
- avoids turning the model node into a grab bag of output policies
- keeps parser errors clearly attributable to decoding rather than transport or model invocation

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
5. `ChatRequest => ChatResponse` model node
6. prompt/template node
7. typed parser node
8. one end-to-end example in docs/tests

This branch now implements that narrower core except for `AiAgent`/workflow/RAG adapters, which are intentionally deferred until the execution model settles.

## Acceptance Criteria For A Future Implementation

- A simple prompt -> model -> parser chain is expressible as a single typed value.
- A parallel fanout + merge flow is expressible without custom orchestration code.
- Existing `AiAgent`, `WorkflowAgent`, and RAG components can be adapted without invasive rewrites.
- The new layer improves usability without obscuring runtime semantics or errors.
- The design is documented in `docs/USAGE.md` only after the API stabilizes.
