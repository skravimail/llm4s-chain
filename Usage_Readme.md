# Native Scala 3 Usage Guide

This guide explains how to use the native `llm4s-*` modules implemented on the `l4jOnly_codex` branch. It is usage-focused; the PR history and delivery plan live in `Readme_PR_plan.md`.

## Architecture

```mermaid
flowchart TB
  app["Application code / examples"]
  legacy["Legacy LangChain4j demos\n(src/main/scala)"]

  macros["llm4s-macros\nTrait materialization\n@system / @user / @tool"]
  structured["llm4s-structured\nTyped structured outputs\nJSON Schema + decoding"]
  runtime["llm4s-runtime\nChat loop\nTool loop\nInvocationContext"]
  streaming["llm4s-streaming\nStream events\nStreaming runtime"]
  openai["llm4s-openai-compat\nOpenAI-compatible HTTP/SSE backend"]
  tools["llm4s-tools\nTool schema derivation\nArgument decoding"]
  memory["llm4s-memory\nSession memory\nWindow memory"]
  rag["llm4s-rag\nEmbeddings\nRetrieval\nAugmentation\nReranking hooks"]
  agentic["llm4s-agentic\nSequence / parallel / conditional / loop\nSupervisor orchestration"]
  mcp["llm4s-mcp\nMCP JSON-RPC client\nstdio / HTTP transports\nMCP ToolKit adapter"]
  guardrails["llm4s-guardrails\nInput/output/tool guardrails\nModeration hooks\nRetry policy"]
  core["llm4s-core\nProvider-neutral ADTs\nMessages / content / tools\nSchemas / capabilities"]

  provider["OpenAI-compatible provider"]
  mcpServer["MCP servers"]
  vectorStore["Embedding stores\nIn-memory / pgvector boundary"]

  app --> macros
  app --> runtime
  app --> streaming
  app --> rag
  app --> agentic
  app --> mcp
  app --> guardrails

  macros --> runtime
  macros --> structured
  macros --> tools
  macros --> memory

  structured --> runtime
  streaming --> runtime
  tools --> runtime
  guardrails --> runtime
  guardrails --> core
  mcp --> runtime
  rag --> core
  agentic --> core

  runtime --> core
  structured --> core
  streaming --> core
  tools --> core
  memory --> core
  mcp --> core
  openai --> core

  runtime --> openai
  streaming --> openai
  openai --> provider
  mcp --> mcpServer
  rag --> vectorStore

  legacy -. temporary until post-roadmap cleanup .-> app
```

## Module Map

- `llm4s-core`: provider-neutral messages, content, tools, schemas, response formats, usage, finish reasons, and model capabilities.
- `llm4s-openai-compat`: OpenAI-compatible HTTP and SSE wire support.
- `llm4s-runtime`: chat execution and tool-call loop.
- `llm4s-tools`: Scala 3 derivation for tool schemas and argument decoding.
- `llm4s-macros`: trait-based AI service materialization with `@system`, `@user`, `@tool`, and `@param`.
- `llm4s-structured`: typed output decoding with JSON Schema response format.
- `llm4s-streaming`: streaming events and token collection.
- `llm4s-memory`: session memory and window memory.
- `llm4s-rag`: embeddings, stores, retrieval, augmentation, advanced routing, aggregation, and reranking.
- `llm4s-agentic`: typed workflows and supervisor orchestration.
- `llm4s-mcp`: MCP JSON-RPC client, stdio/HTTP transports, and MCP-to-`ToolKit` adapter.
- `llm4s-guardrails`: input/output/tool guardrails, moderation hooks, and retry policy.

## Provider Setup

Use an OpenAI-compatible backend when calling a live model. The backend depends on an `OpenAiTransport`; the provided STTP transport handles HTTP calls.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.openai.OpenAiCompatBackend
import org.l4j.template.llm4s.openai.OpenAiCompatConfig
import org.l4j.template.llm4s.openai.SttpOpenAiTransport
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Uri

val backendResource =
  AsyncHttpClientCatsBackend.resource[IO]().map { sttp =>
    val transport = SttpOpenAiTransport[IO](
      baseUri = Uri.unsafeParse("https://api.openai.com/v1"),
      backend = sttp,
    )

    OpenAiCompatBackend[IO](
      OpenAiCompatConfig(
        baseUrl = "https://api.openai.com/v1",
        apiKey = sys.env("OPENAI_API_KEY"),
        model = "gpt-4.1-mini",
      ),
      transport,
    )
  }
```

The same backend contract works with any OpenAI-compatible endpoint by changing `baseUri`, `apiKey`, and `model`.

## Plain Chat

Use `AiRuntime` directly when you want explicit request/runtime control.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.ToolKit

def answer(backend: org.l4j.template.llm4s.core.ChatBackend[IO]): IO[String] =
  AiRuntime[IO](backend).chat(
    system = Some("You are concise."),
    userText = "Explain Scala 3 opaque types in one paragraph.",
    toolKit = ToolKit.empty[IO],
  )
```

Use `ChatRequest` directly when you need full control over messages, tools, response format, temperature, or metadata.

```scala
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.runtime.AiRuntime

val request = ChatRequest(
  messages = List(
    ChatMessage.SystemMessage.from("Answer as a senior Scala engineer."),
    ChatMessage.UserMessage.from("When should I use inline methods?"),
  ),
  temperature = Some(0.2),
)

val effect = AiRuntime[IO](backend).chatRequest(request)
```

## Trait-Based AI Services

Use `llm4s-macros` when you want a LangChain4j-style typed service interface without runtime reflection.

```scala
import scala.annotation.experimental
import org.l4j.template.llm4s.macros.AiService
import org.l4j.template.llm4s.macros.system
import org.l4j.template.llm4s.macros.user

@experimental
trait Greeter:
  @system("You are friendly and concise.")
  @user("Say hello to {{name}}")
  def greet(name: String): String

@experimental
val greeter = AiService.materialize[Greeter](backend)

val text: String = greeter.greet("Ada")
```

Prompt placeholders must match method parameter names. Invalid placeholders fail at compile time.

## Tools

Use `ToolDefinition.fromProduct` to derive tool schema and argument decoding from a Scala product type.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.core.ToolResult
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.tools.ToolDefinition
import org.l4j.template.llm4s.tools.SchemaEncoder.given
import org.l4j.template.llm4s.tools.ValueDecoder.given

final case class WeatherArgs(city: String, units: Option[String])

val weatherTool =
  ToolDefinition.fromProduct[IO, WeatherArgs](
    name = "weather",
    description = "Fetches current weather for a city.",
  ) { args =>
    IO.pure(ToolResult.Text(s"${args.city}: 72 degrees"))
  }

val toolKit: ToolKit[IO] = weatherTool.toToolKit

val result = AiRuntime[IO](backend).chat(
  system = Some("Use tools when useful."),
  userText = "What is the weather in Chicago?",
  toolKit = toolKit,
)
```

Supported derived inputs include primitives, nested case classes, enums, `Option`, and `List`.

## Structured Outputs

Use `StructuredCodec.derived` for typed case-class outputs. The runtime requests JSON Schema output when supported and decodes the final assistant response.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.structured.StructuredOutputRuntime
import org.l4j.template.llm4s.tools.SchemaEncoder.given
import org.l4j.template.llm4s.tools.ValueDecoder.given

final case class Summary(title: String, bullets: List[String])

given StructuredCodec[Summary] = StructuredCodec.derived[Summary]

val summary: IO[Summary] =
  StructuredOutputRuntime.chat[IO, Summary](
    backend = backend,
    config = org.l4j.template.llm4s.runtime.RuntimeConfig(),
    system = Some("Return only structured output."),
    userText = "Summarize the benefits of Scala 3.",
  )
```

Structured outputs also work through `AiService.materialize` when a `StructuredCodec[A]` or `upickle` reader is available for the return type.

## Streaming

Use `StreamingAiRuntime` with a `StreamingChatBackend`.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.streaming.StreamingAiRuntime
import org.l4j.template.llm4s.streaming.StreamEvent

val stream = StreamingAiRuntime[IO](streamingBackend).stream(
  system = Some("Be concise."),
  userText = "Stream a short explanation of effect types.",
)

val text: IO[String] = stream.collectText

val events = stream.events.evalMap {
  case StreamEvent.TextDelta(value) => IO.println(value)
  case other                        => IO.println(other.toString)
}
```

The native event model supports text deltas, thinking deltas, tool-call deltas, completed tool calls, and final completion.

## Memory

Use `InMemoryChatMemory` for session memory, and wrap it with `MessageWindowMemory` to keep only recent messages.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.memory.InMemoryChatMemory
import org.l4j.template.llm4s.memory.MemoryId
import org.l4j.template.llm4s.memory.MessageWindowMemory
import org.l4j.template.llm4s.runtime.AiRuntime

val program =
  for
    base <- InMemoryChatMemory.create[IO, MemoryId]
    memory = MessageWindowMemory[IO, MemoryId](base, maxMessages = 20)
    runtime = AiRuntime[IO](backend)
    first <- runtime.chatWithMemory(memory, MemoryId("user-123"), Some("Remember context."), "My name is Ada.")
    second <- runtime.chatWithMemory(memory, MemoryId("user-123"), Some("Remember context."), "What is my name?")
  yield second
```

`AiService.materialize` also has a memory-aware overload that accepts `ChatMemory[IO, MemoryId]` and `MemoryId`.

## RAG

Use `EmbeddingModel`, `EmbeddingStore`, and `ContentRetriever` to build retrieval. `DefaultRetrievalAugmentor` rewrites the last user message with retrieved context and returns the sources.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.rag.*

val embeddingModel = new EmbeddingModel[IO]:
  override def embed(text: String): IO[EmbeddingVector] =
    IO.pure(EmbeddingVector.of(1.0, 0.0))

val program =
  for
    store <- InMemoryEmbeddingStore.create[IO]
    _ <- store.add(
      List(
        EmbeddingRecord("scala", "Scala 3 supports inline macros.", EmbeddingVector.of(1.0, 0.0))
      )
    )
    retriever = EmbeddingContentRetriever[IO](embeddingModel, store, maxResults = 3)
    augmentor = DefaultRetrievalAugmentor[IO](retriever)
    augmented <- augmentor.augment(
      ChatRequest(List(ChatMessage.UserMessage.from("How do Scala macros work?")))
    )
    answer <- AiRuntime[IO](backend).chatRequest(augmented.request)
  yield answer -> augmented.sources
```

Use `AdvancedContentRetriever` when you need query transformation, multi-retriever routing, de-duplication, reranking, and result limiting.

```scala
import org.l4j.template.llm4s.rag.AdvancedContentRetriever
import org.l4j.template.llm4s.rag.QueryRouter
import org.l4j.template.llm4s.rag.QueryTransformer
import org.l4j.template.llm4s.rag.ReRanker

val advanced = AdvancedContentRetriever[IO](
  queryTransformer = QueryTransformer.static[IO](q => List(q, s"$q examples")),
  queryRouter = QueryRouter.static[IO](List(retriever)),
  reRanker = ReRanker.byScore[IO],
  maxResults = 4,
)
```

## Agentic Workflows

Use `Agent` and `Workflow` for deterministic orchestration. Workflows share an `AgentScope`.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.agentic.*

val writer = Agent.liftScoped[IO, String, String]("writer") { (topic, _) =>
  IO.pure(s"Story about $topic")
}

val editor = Agent.liftScoped[IO, String, String]("editor") { (story, _) =>
  IO.pure(story.toUpperCase)
}

val workflow = writer.workflow.andThen(editor.workflow)

val program =
  for
    scope <- AgentScope.create[IO]
    result <- workflow.run("Scala", scope)
    trace <- scope.snapshot
  yield result -> trace
```

Use `ParallelWorkflow`, `ConditionalWorkflow`, and `LoopWorkflow` for deterministic branches and loops.

```scala
val parallel = ParallelWorkflow(writer.workflow, editor.workflow)
```

Use `SupervisorAgent` when a planner should select sub-agents dynamically.

```scala
val registry = SubAgentRegistry.of(writer, editor)

val planner = SupervisorAgent.planner[IO, String, String] { (input, _, _) =>
  IO.pure(
    List(
      PlanStep("writer"),
      PlanStep("editor", input = Some(s"Edit: $input"), outputKey = Some("edited")),
    )
  )
}

val supervisor = SupervisorAgent[IO, String, String](
  name = "supervisor",
  registry = registry,
  planner = planner,
  aggregate = (_, results, _) => IO.pure(results.map(_.output).mkString("\n")),
)
```

## MCP Tools

Use `McpClient` and `McpToolProvider` to expose MCP server tools as a native `ToolKit`.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.mcp.McpClient
import org.l4j.template.llm4s.mcp.McpToolProvider
import org.l4j.template.llm4s.mcp.StdioMcpTransport
import org.l4j.template.llm4s.runtime.AiRuntime

val program =
  for
    transport <- StdioMcpTransport.create[IO](
      readLine = IO.raiseError(RuntimeException("wire this to process stdout")),
      writeLine = line => IO.println(line),
    )
    toolKit <- McpToolProvider(McpClient[IO](transport)).toolKit
    answer <- AiRuntime[IO](backend).chat(
      system = Some("Use MCP tools when useful."),
      userText = "Use the available tools to answer.",
      toolKit = toolKit,
    )
  yield answer
```

`HttpMcpTransport` is available for HTTP MCP endpoints. `StdioMcpTransport` is intentionally line-based so applications can wire it to a managed process however they prefer.

## Guardrails And Moderation

Wrap a backend with `GuardedChatBackend` to run input/output guardrails around model calls. Wrap a `ToolKit` with `GuardedToolKit` to guard tool calls.

```scala
import cats.effect.IO
import org.l4j.template.llm4s.guardrails.*

val moderation = new ModerationModel[IO]:
  override def moderate(text: String): IO[ModerationResult] =
    IO.pure(ModerationResult(flagged = text.contains("blocked"), categories = Set("policy")))

val guardrails = GuardrailChain[IO](
  input = List(ModerationGuardrails.input(moderation)),
  output = List(ModerationGuardrails.output(moderation)),
)

val guardedBackend = GuardedChatBackend[IO](
  underlying = backend,
  guardrails = guardrails,
  retryPolicy = RetryPolicy.retryAll(maxAttempts = 2),
)
```

For tool guardrails:

```scala
val guardedTools = GuardedToolKit(toolKit, GuardrailChain(tools = List(myToolGuardrail)))
```

Blocked input/output raises `GuardrailBlockedException`. Blocked tool calls return an error `ToolResult` so the tool loop can continue in a model-visible way.

## Multimodal Requests

Use `AiContent.Image` and `AiContent.File` in user messages. `ChatRequest.requiredCapabilities` tells you what the request needs from a provider.

```scala
import org.l4j.template.llm4s.core.AiContent
import org.l4j.template.llm4s.core.ChatMessage
import org.l4j.template.llm4s.core.ChatRequest
import org.l4j.template.llm4s.core.ModelCapabilities
import org.l4j.template.llm4s.core.ModelCapability

val request = ChatRequest(
  messages = List(
    ChatMessage.UserMessage(
      List(
        AiContent.Text("Summarize this image and file."),
        AiContent.Image("base64-image", "image/png", detail = Some("high")),
        AiContent.File("base64-pdf", "application/pdf", fileName = Some("brief.pdf")),
      )
    )
  )
)

val capabilities = ModelCapabilities(
  Set(ModelCapability.VisionInput, ModelCapability.FileInput)
)

val validation = capabilities.validate(request)
```

The OpenAI-compatible encoder emits mixed content parts for text, images, and files.

## Capability Checks

Use capability validation before sending a request to a provider when you maintain provider metadata.

```scala
val missing = capabilities.missing(request.requiredCapabilities)

capabilities.validate(request) match
  case Right(_) => // safe to submit
  case Left(error) => // choose a different model or reject early
```

Capabilities currently include:

- `ToolCalling`
- `StructuredOutputJsonSchema`
- `Streaming`
- `VisionInput`
- `FileInput`
- `MultimodalToolResult`
- `Thinking`
- `Embeddings`
- `Moderation`

## Testing

Run focused module tests while developing one feature:

```bash
env SBT_OPTS=-Dsbt.boot.directory=/Users/alpha/AI_ML/llm4s-template/.sbt-boot\ -Dsbt.ivy.home=/Users/alpha/AI_ML/llm4s-template/.ivy2 COURSIER_CACHE=/Users/alpha/AI_ML/llm4s-template/.coursier sbt 'llm4sCore/test'
```

Run the full explicit module sweep before committing cross-module changes:

```bash
env SBT_OPTS=-Dsbt.boot.directory=/Users/alpha/AI_ML/llm4s-template/.sbt-boot\ -Dsbt.ivy.home=/Users/alpha/AI_ML/llm4s-template/.ivy2 COURSIER_CACHE=/Users/alpha/AI_ML/llm4s-template/.coursier sbt 'llm4sCore/test' 'llm4sMemory/test' 'llm4sRag/test' 'llm4sAgentic/test' 'llm4sMcp/test' 'llm4sGuardrails/test' 'llm4sRuntime/test' 'llm4sStreaming/test' 'llm4sOpenAiCompat/test' 'llm4sTools/test' 'llm4sStructured/test' 'llm4sMacros/test'
```

## Current Caveat

The native framework modules are complete through the framework-parity roadmap. The root project still includes legacy LangChain4j dependencies because the old demo code under `src/main/scala/org/l4j/template/l4j_macro` still references LangChain4j. The next cleanup step is to move that legacy code into a separate demo module, rewrite it against native `llm4s-*`, or remove it.

