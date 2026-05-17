

# llm4s-template — Code Review

A prioritised list of improvements identified during a walk‑through of the
codebase (90 Scala sources across 11 modules). Ordered by impact: items at the
top are the highest‑leverage changes; items at the bottom are polish.

The project is competent and internally consistent — the points below are rough
edges, not deal‑breakers.

---

## 1. Typed error ADT instead of `RuntimeException(String)`

> ✅ **Fixed** — 2026-05-17 in `2a4260d`.
> `AiRuntimeError` sealed hierarchy added under `llm4s-runtime`
> (`MaxTurnsExceeded`, `ContentFiltered`, `ProviderError`, `ToolMissing`,
> `ToolFailed`). `AiRuntime.loop` now raises typed errors; tests assert
> pattern‑matching works.

The codebase invests heavily in typed data (`ChatMessage`, `FinishReason`,
`ToolResult`, `GuardrailResult`) and then signals chat failures with:

```scala
MonadThrow[F].raiseError(RuntimeException(s"AiRuntime chat exceeded ${config.maxTurns} ..."))
```

There is no sealed `AiRuntimeError` hierarchy, no error code, nothing a caller
can pattern‑match on. A library that *requires* `MonadThrow` should ship a
sealed error ADT — e.g. `MaxTurnsExceeded`, `ContentFiltered`, `ProviderError`,
`ToolMissing`, `ToolFailed(name, cause)` — and raise typed instances. Today you
have to grep error strings to react to failures.

## 2. Wrong effect constraints and missing stack‑safety in the chat loop

> ✅ **Fixed** — 2026-05-17 in `a131773`.
> `AiRuntime.loop` is now expressed as `Monad[F].tailRecM` over
> `(turn, request)`, so any monad with a stack‑safe `tailRecM`
> (cats‑effect `IO`, `Eval`, …) drives it without exhausting the JVM
> stack. A 5000‑turn test was added. `MonadThrow` is retained where it
> is actually needed (raising typed errors / `handleErrorWith`).

`ToolLoop.executeAll`, `AiRuntime.loop`, `GuardrailChain.checkInput` all demand
`MonadThrow[F]`, but several of those paths never raise — they only
`pure`/`map`/`flatMap`. Conversely, `AiRuntime.loop`’s recursion is **not
stack‑safe** under monads that aren’t `Sync`/`Defer` (e.g. a naive `cats.Id`
chosen for tests will stack‑overflow at a high `maxTurns`).

The right constraints are:

- `Monad[F]` where errors aren’t raised,
- `MonadError[F, Throwable]` where they are,
- `Defer[F]` (or `tailRecM`) for the chat loop itself.

Using `MonadThrow` everywhere is a soft over‑constraint that also hides the
stack‑safety issue.

## 3. `ToolKit` stores schemas and executors in two unrelated collections

> ✅ **Fixed** — 2026-05-17 in `53a144f`.
> Repackaged as `Map[String, ToolEntry[F]]` with the schema + executor
> paired by construction. New constructors `ToolKit.of`,
> `ToolKit.fromPairs`, `withEntry`, and a backwards‑compatible
> `apply(schemas, executors)` that *fails loudly* on the orphan / typo
> case (the existing `GuardrailsSpec` was relying on that footgun and
> was fixed in the same commit).

```scala
final case class ToolKit[F[_]](
    schemas: List[ToolSchema],
    executors: Map[String, ToolExecutor[F]],
)
```

Nothing enforces that every `ToolSchema.name` has a corresponding executor, or
vice versa. `++` happily produces a `ToolKit` with a schema for `"search"` and
an executor for `"serach"`. A safer shape is `Map[String, ToolEntry[F]]` where
each entry pairs schema + executor and the name is held once.

## 4. No `derives` for tool schemas — boilerplate per tool

> ✅ **Fixed** — 2026-05-17 in `9e01c71`.
> Added a `ToolDef[A]` typeclass (in `llm4s-tools`) that bundles both
> `SchemaEncoder` and `ValueDecoder` in one inline‑derived instance.
> Tool authors now write `case class Args(...) derives ToolDef` and
> register with `ToolDefinition.fromArgs[F, Args]`. The legacy
> `fromProduct` keeps working unchanged.

The README advertises “compile‑time tool argument decoding” and “no macros”.
Both can be true, but in practice tool authors hand‑write a `ToolSchema` (a
`JsonSchema` ADT) **and** a `ValueDecoder[A]` per tool. For anything beyond the
demo `CvReview` this is tedious and error‑prone — schema and decoder drift
apart silently.

A `derives` clause backed by Scala 3 inline derivation (no macro annotations,
no `@experimental`) would give you `case class Args(...) derives ToolDef` and
keep both promises. The current gap is the single biggest usability cost of
the library.

## 5. `Resource[F, _]` discipline for HTTP‑owning backends

> ✅ **Fixed** — 2026-05-17 in `0f28ce3`.
> Added `OpenAiCompatBackend.resource[F: Async](config): Resource[F, ChatBackend[F]]`
> which builds and owns the full sttp `AsyncHttpClient` stack, and
> `OpenAiCompatStreamingBackend.resource(...)` for the streaming variant.
> The demo `BackendSupport` was migrated to the new factory. Tests
> assert finalizers fire on `.use` exit.

`OpenAiCompatBackend` takes an `OpenAiTransport[F]` by value. The underlying
sttp backend holds thread pools and an HTTP client. The library never
expresses lifetime — there is no
`OpenAiCompatBackend.resource[F]: Resource[F, ChatBackend[F]]`. Users who
instantiate it the obvious way will leak HTTP client resources on shutdown.
For a cats‑effect‑native library this is a notable omission.

## 6. Sequential guardrail and tool execution where parallel is correct

> ✅ **Fixed** — 2026-05-17 in `992c8a2`.
> `GuardrailChain.checkInput/checkOutput/checkTool` now require
> `Parallel[F]` and `parTraverse` the registered guardrails (first
> block wins). Sequential transform‑chain semantics are retained as
> opt‑in `checkInputSequential` (and its output/tool siblings).
> `ToolLoop.executeAll` now `parTraverse`s tool calls. `Parallel[F]`
> threaded through `AiRuntime`, `AiAgent`, `StructuredOutputRuntime`,
> `GuardedChatBackend`, and `GuardedToolKit`. Sleep‑and‑measure tests
> prove the parallelism.

- `GuardrailChain.checkInput` `foldLeft`s through input guardrails one at a
  time. Most of those are independent checks; on async I/O (e.g. moderation
  calls) you pay latency you don’t need to. `parTraverse` over independent
  guardrails, then aggregate, would be both faster and more honest about what
  the model is.
- `ToolLoop.executeAll` uses `.traverse` — sequential. The OpenAI tool spec
  allows the model to emit *parallel* tool calls in one turn, and most
  providers expect them to be answered after running them in parallel.
  Sequential traversal visibly slows down any agent that does fan‑out.

Both should default to parallel and let the caller opt back to sequential, not
the other way around.

## 7. `ToolLoop` swallows tool failures and silently continues

> ✅ **Fixed** — 2026-05-17 in `cb9b1e1`.
> Added `RuntimeConfig.toolFailurePolicy` and
> `RuntimeConfig.unknownToolPolicy`, each accepting
> `ToolErrorPolicy.SurfaceToModel | FailFast | RetryOnce`. Tool
> failures default to `SurfaceToModel` (so the model can recover);
> unknown tools default to `FailFast` (typed `AiRuntimeError.ToolMissing`)
> — the model asking for a non‑existent tool is almost always a
> schema bug worth surfacing loudly. `ToolLoop.executeAll` dispatches
> per policy and is covered by five new tests.

```scala
executor.execute(toolCall, context).handleErrorWith(errorResult)   // returns isError=true
```

Every tool error is caught, JSON‑wrapped, and fed back to the model. That is
*one* reasonable strategy, but it is wired in unconditionally. There is no
policy knob (`failFast` vs `surfaceToModel` vs `retryOnce`) and no telemetry
hook saying “a tool blew up.” In production this is exactly the kind of thing
that hides bugs for weeks. The policy belongs on `RuntimeConfig`.

An *unknown* tool gets the same treatment (`"no such tool: …"`). That should
probably be a typed error — the model asking for a non‑existent tool usually
means the schema you sent it is wrong, and silently feeding the model an
error blob just trains it to keep doing it.

## 8. No telemetry / tracing seams

For a library aimed at agentic workflows there is no `Trace[F]` typeclass, no
hook to emit per‑turn / per‑tool events, no metric counters. People will write
production agents with this and immediately need to bolt observability on. A
minimal `RuntimeListener[F]` (chat‑started, chat‑completed, tool‑called,
tool‑failed, guardrail‑blocked) would cost very little and save adopters a
fork.

## 9. Thin tests for the provider‑neutral surface area

90 source files; the test files visible are one per module (`AiRuntimeSpec`,
`AgenticWorkflowSpec`, `RagSpec`, etc.). For a library that promises
“provider‑neutral,” there should also be:

- A `ChatBackend` *law check* (every backend must obey: tool‑call round‑trips,
  finish‑reason semantics, message ordering preserved).
- A streaming‑decoder test fed by a corpus of recorded SSE traces from real
  providers (OpenAI, Anthropic‑via‑compat, OMLX, vLLM). The decoder is exactly
  the kind of code that breaks subtly when a provider tweaks its event shape.
- Property tests for `JsonSchema` ↔ wire round‑trips.

None of these were observed.

## 10. JSON is hand‑built with string interpolation in at least one place

`GuardedToolExecutor` builds JSON like this:

```scala
s"""{"error":"tool call blocked by guardrail","code":"${escape(violation.code)}","message":"${escape(violation.message)}"}"""
```

with a hand‑rolled `escape` that only handles `\` and `"`. Control characters,
non‑BMP code points, and embedded newlines will produce invalid JSON. The
project already depends on uPickle — use `ujson.Obj(...)` like `ToolLoop`
does. This is a real bug, not just a style preference.

## 11. `dropLeadingSystem` is a hack that hides a real modelling problem

```scala
private def dropLeadingSystem(messages: List[ChatMessage]): List[ChatMessage] =
  messages match
    case (_: ChatMessage.SystemMessage) :: tail => tail
    case other                                  => other
```

This silently assumes there is at most one system message and that it is
always first. Both assumptions can break (some flows use multiple system
messages; the system message can be in any position depending on the caller).
The persistence boundary deserves a real model — e.g.
`ChatTranscript(system: Option[SystemMessage], turns: List[Turn])` — so you do
not carry “oops, strip the system prompt” logic around.

## 12. `AiAgent` constructs a fresh `AiRuntime` per agent and per copy

```scala
private val runtime: AiRuntime[F] = AiRuntime[F](backend, config)
...
def withTools(tk: ToolKit[F]): AiAgent[F] = new AiAgent[F](backend, tk, config)
```

`AiRuntime` is cheap, so this is not a perf problem — it is a *semantics*
problem. If `AiRuntime` ever acquires state (rate limiter, in‑flight counter,
cache), this pattern silently duplicates it. Either commit to “runtime is a
value, no state ever” in the type (e.g. make it a `case class`) or share one
runtime across derived agents.

## 13. `AiAgent` surface is asymmetric and cramped

`AiAgent` exposes `chat`, `chatAs`, `chatWithMemory`, `chatAsWithMemory` — but
only `(system, user)` shapes. There is no:

- multi‑turn user input (`List[ChatMessage]`),
- per‑call tool override,
- per‑call temperature / max‑tokens / response‑format,
- streaming (you have `StreamingAiRuntime` but no `AiAgent.stream*`).

A more honest API would be a single
`chat(request: ChatRequest, opts: ChatOptions): F[A]` with helpers for the
common cases, instead of a 2×2 method grid that will not survive the next
feature request without becoming 2×2×N.

## 14. Module boundaries leak

- `llm4s-guardrails` depends on `llm4s-runtime` only because `ToolGuardrail`
  needs `InvocationContext`. That type lives in `llm4s-runtime` but is really
  a *cross‑cutting* value (also referenced by `llm4s-tools`). It probably
  belongs in `llm4s-core`, and `guardrails` should not depend on `runtime`.
- `llm4s-runtime` depends on `llm4s-memory` purely so `AiRuntime` can offer
  `chatWithMemory` convenience methods. That couples the runtime to a specific
  persistence abstraction. A cleaner split: `AiRuntime` knows nothing about
  memory; the memory module provides a small `MemoryAware[F]` wrapper or a
  syntax extension.

## 15. Build hygiene

- Versions are scattered as `val sttpVersion = "..."` at the top of
  `build.sbt` but never centralised into a `project/Dependencies.scala`.
  Eleven sub‑projects each repeat `cats-effect`, `upickle`, `testDeps` —
  adding a module is copy‑paste.
- `commonSettings` does not enable `-Wvalue-discard`, `-Wnonunit-statement`,
  `-Xfatal-warnings`, or `-Ykind-projector`. For a tagless‑final library this
  is a real loss: discarded `F[Unit]` values currently compile silently.
- `Compile / scalafmtOnCompile := false` on root — the README tells users to
  run `sbt scalafmtAll` manually. CI does it but local dev does not. Either
  commit to it or drop scalafmt from the docs.
- `root` depends on every sub‑project, so `sbt compile` at root rebuilds the
  world; there is no aggregated build target separate from the demo runner.
  Splitting `aggregate` (everything) from `root` (just demos) would make CI
  noticeably faster.
- `Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat` is
  copy‑pasted into nine sub‑projects with no comment explaining *why*. That
  is a smell — either it belongs in `commonSettings`, or the comment that
  justifies it belongs next to each occurrence.

## 16. Documentation is split awkwardly

`README.md`, `Usage_Readme.md`, `Readme_PR_plan.md` — three READMEs at root
with overlapping content. The PR plan is delivery history (belongs in
`CHANGELOG.md` or PR descriptions), the usage doc is per‑module API help
(belongs in module READMEs or scaladoc), and the top‑level README should be a
60‑second pitch. Right now a newcomer does not know which file is canonical.

## 17. Naming nits that will compound

- `AiRuntime` vs `StreamingAiRuntime` vs `AiAgent` vs `Agent` (the workflow
  agent). Four overlapping concepts using two words. `Agent[F,In,Out]` in
  `llm4s-agentic` and `AiAgent[F]` in `llm4s-structured` are *different
  things* — predictable confusion.
- `ChatBackend` / `StreamingChatBackend` are separate traits, not one trait
  with two methods or two views of one capability. If a backend implements
  both, users see two values and two wirings. A `ChatBackend[F]` with `chat`
  and an optional `stream` (or an `F`‑polymorphic `Either`‑shaped result)
  would model reality better.
- `org.l4j.template.llm4s.*` — five path segments before you reach a
  meaningful name. Pick one of `l4j` / `llm4s` / `template`.

---

## Suggested order of attack

1. **#1, #2** — typed error ADT and `tailRecM`‑based stack‑safe loop. Cheap,
   big correctness win.
2. **#3, #4** — `ToolKit` as `Map[String, ToolEntry]` and `derives ToolDef`.
   Removes the most painful adopter footgun.
3. **#5** — `Resource[F, _]` for the OpenAI backend. Anyone using this in
   earnest will hit it.
4. **#6** — parallel guardrails and parallel tool calls. Free latency.
5. **#8, #9** — `RuntimeListener[F]` and provider‑law tests. Unlocks real
   adoption.

Everything else (naming, build hygiene, doc layout) is polish you can defer.
