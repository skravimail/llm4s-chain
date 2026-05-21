# Anti-G Traceability Roadmap

This document outlines 5 key features that can be implemented to further improve the traceability and observability of the `llm4s` framework beyond the current `Natchez` foundations.

## 1. OpenTelemetry (OTEL) Integration
Currently, the project integrates with `natchez`. While `natchez` supports Datadog/Honeycomb, building a dedicated **OpenTelemetry** module (`llm4s-tracing-otel`) would allow emitting standardized W3C trace headers, spans, and attributes natively to OTEL collectors (Grafana Tempo, Jaeger, etc.). This ensures vendor-neutral observability and easier adoption for enterprise users.

## 2. MCP Tool Context Propagation
Traceability currently stops at the boundary of external tools. `llm4s-mcp` can be updated to propagate the current `TraceContext` (Trace ID + Span ID) downstream inside the JSON-RPC request metadata. This way, if an MCP server queries a database, that DB query will appear as a child span of the LLM's tool-call span in the tracing UI, providing full distributed tracing across agent-tool boundaries.

## 3. Detailed Token & Cost Attribution (Metric Counters)
While `RuntimeListener` surfaces durations (`durationNanos`) and success/fail states, it does not currently expose granular token usage or cost calculation metrics per step. Injecting `Usage` statistics into the `Span` attributes (e.g., `llm.usage.prompt_tokens`, `llm.usage.completion_tokens`) would allow building dashboards that track cost-per-workflow-step or per-agent-run.

## 4. DSL Graph Telemetry (`llm4s-dsl`)
The new LCEL-style DSL (`llm4s-dsl`) has node names (e.g., `.named("Summary Parser")`), but it does not yet emit tracing spans natively per DSL hop. We can thread the `NatchezWorkflowListener` or `RuntimeListener` deeply into the DSL `RunContext` so that every `>>` or `|` operator emits its own child span (e.g., `span("Prompt Builder") -> span("LLM Call") -> span("JSON Parser")`).

## 5. Payload Masking / Safe Auditing
Tracing LLM apps often involves logging the raw prompt text and model output, which is a massive PII/security risk. The framework should introduce a `TelemetryScrubber[F]` trait or `LogMasking` capability that redacts sensitive PII or scrubs secret API keys from the span fields before `natchez` or OTEL emits them.
