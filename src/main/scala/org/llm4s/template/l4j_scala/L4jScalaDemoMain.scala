package org.llm4s.template.l4j_scala

import dev.langchain4j.agent.tool.{ P, Tool }
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.service.{ SystemMessage, UserMessage, V }

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

/**
 * End-to-end demo of the `l4j_scala` wrapper layer:
 *   1. Build a ChatModel pointed at the local OMLX server.
 *   2. Ask a one-shot prompt via the Chat extension methods.
 *   3. Build an AiServices proxy with a tool registered for the model to call.
 *   4. Index a small in-memory document corpus, attach a RetrievalAugmentor, query it.
 *
 * Run with:
 *   sbt "runMain org.llm4s.template.l4j_scala.L4jScalaDemoMain"
 *
 * Override the model with env vars `LANGCHAIN4J_BASE_URL` / `LANGCHAIN4J_API_KEY` / `LANGCHAIN4J_MODEL`.
 */
object L4jScalaDemoMain {

  // 1. Tool definition — Scala class with @Tool-annotated methods. langchain4j discovers
  //    these via reflection at AiServices build time.
  class WikiLookup {
    @Tool(Array("Look up a one-line definition for a programming term"))
    def define(@P("Term to look up") term: String): String = term.toLowerCase match {
      case "monad"   => "A monad is a design pattern that defines how functions can be combined."
      case "functor" => "A functor maps elements of one set to another while preserving structure."
      case _         => s"No definition found for '$term'"
    }
  }

  // 2. AiService interface — typed in/out, optional RAG augmentor will be attached at build time.
  trait DocsQa {
    @SystemMessage(Array("You answer questions using only the provided context. Be concise."))
    @UserMessage(Array("Question: {{question}}"))
    def answer(@V("question") q: String): String
  }

  trait Assistant {
    @SystemMessage(Array("You are a helpful programming tutor. Use tools if you need a definition."))
    def chat(message: String): String
  }

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = ExecutionContext.global

    val baseUrl   = sys.env.getOrElse("LANGCHAIN4J_BASE_URL", "http://localhost:8000/v1")
    val apiKey    = sys.env.getOrElse("LANGCHAIN4J_API_KEY", sys.env.getOrElse("OMLX_API_KEY", "dummy"))
    val modelName = sys.env.getOrElse("LANGCHAIN4J_MODEL", "gemma-4-e4b-it-4bit")

    val chatModel = OpenAiChatModel
      .builder()
      .baseUrl(baseUrl)
      .apiKey(apiKey)
      .modelName(modelName)
      .build()

    // --- 1. Chat: extension method, returns Future[String] ---
    import Chat._
    val oneShotF = chatModel.askAsync("In one sentence, what is referential transparency?")
    val oneShot  = Await.result(oneShotF, 2.minutes)
    println("\n[1/3] One-shot chat:")
    println(s"  $oneShot")

    // --- 2. AiService with a tool ---
    val assistant = AiService.build[Assistant](
      chatModel,
      AiService.Config(tools = Seq(new WikiLookup)),
    )
    println("\n[2/3] AiService with tool:")
    println(s"  ${assistant.chat("Explain what a monad is. Use your definition tool first.")}")

    // --- 3. RAG: index a small corpus, attach an augmentor, query it ---
    val corpus = Seq(
      Rag.segment(
        "Scala 3 introduces given/using replacing implicits. Givens declare implicit values; using imports them at the call site.",
        Map("source" -> "scala3-givens"),
      ),
      Rag.segment(
        "Cats Effect 3 uses fibers for concurrency. A fiber is a lightweight thread of execution scheduled cooperatively.",
        Map("source" -> "cats-effect-fibers"),
      ),
      Rag.segment(
        "ZIO's environment is encoded in the R type parameter of ZIO[R, E, A], which represents the services needed to run the effect.",
        Map("source" -> "zio-environment"),
      ),
    )
    val embedder  = new AllMiniLmL6V2EmbeddingModel() // 384-dim, runs locally via ONNX
    val augmentor = Rag.buildAugmentor(corpus, embedder, maxResults = 2)
    val docsQa    = AiService.build[DocsQa](chatModel, AiService.Config(retrievalAugmentor = Some(augmentor)))

    println("\n[3/3] RAG-augmented Q&A:")
    println(s"  Q: How does Scala 3 replace implicits?")
    println(s"  A: ${docsQa.answer("How does Scala 3 replace implicits?")}")
  }
}
