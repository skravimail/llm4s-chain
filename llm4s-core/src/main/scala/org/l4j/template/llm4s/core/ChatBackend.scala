package org.l4j.template.llm4s.core

trait ChatBackend[F[_]]:
  def chat(request: ChatRequest): F[ChatResponse]

  /** Trace-aware variant. Defaults to the un-traced overload so existing
    * backends keep compiling; backends that want to forward correlation
    * IDs down to lower layers (e.g. HTTP) override this. Added in PR-8f
    * so `AiRuntime` can thread its `TraceContext` end-to-end to the
    * transport without having to special-case provider-specific backends.
    */
  def chat(request: ChatRequest, trace: TraceContext): F[ChatResponse] = chat(request)
