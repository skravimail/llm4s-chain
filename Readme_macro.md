# Macro-based AiService Layer — How It Works

A walkthrough of the Scala 3 macro library at `src/main/scala/org/llm4s/template/l4j_macro/`
and how it plugs into `langchain4j-agentic`'s orchestrator via `AgenticBridge`.

This branch (`scala3`) replaces langchain4j's reflective `AiServices` Proxy with
compile-time class synthesis. Where the reflective layer used `java.lang.reflect.Proxy`
+ runtime annotation scanning, the macro emits a concrete `new T { ... }` whose method
bodies dispatch directly into the chat runtime. `AgenticBridge` is the thin glue that
lets those macro-generated impls participate in the standard agentic orchestrator
(sequence / parallel / conditional / loop / supervisor).

---

## The five-line bridge

```scala
def asAgent[T](impl: T)(using ct: ClassTag[T]): AgentExecutor =
  val iface = ct.runtimeClass.asInstanceOf[Class[T]]
  require(iface.isInterface, ...)
  val agentMethod = AgentUtil.validateAgentClass(iface)
  AgentUtil.nonAiAgentToExecutor(impl, agentMethod)
```

What it produces: an `AgentExecutor` — langchain4j's "this is a fully-wired sub-agent"
record carrying `(invoker, agent)`. Once we hand one of these to `subAgents(...)`, the
orchestrator skips all its own discovery code.

## Why each step exists

### Step 1 — `ct.runtimeClass.asInstanceOf[Class[T]]`

`impl: T` is a Scala value typed as the trait `T`. At runtime its actual class is the
macro-generated anonymous subclass (`$anonAiService123`), not `T`. We need `Class[T]` —
the trait class itself — because:

- The trait class is where the `@Agent` annotation is declared in the bytecode.
- The macro-generated impl class **doesn't carry** that annotation (Java doesn't
  propagate method annotations from interface to implementing class).

The `ClassTag[T]` summoned at the call site reifies `T` to its runtime `Class` object.
`Class[T]` is the trait, not the impl.

### Step 2 — `iface.isInterface`

Defensive. If the user accidentally passes a class type, we'd fail later inside
langchain4j with a worse error. Scala 3 traits compile to JVM interfaces, so
`isInterface == true` is the contract.

### Step 3 — `AgentUtil.validateAgentClass(iface)`

This is langchain4j-agentic's own utility. The implementation (in `AgentUtil.java:308`):

```java
for (Method method : agentServiceClass.getMethods()) {
    if (method.isAnnotationPresent(Agent.class)) { ... }
}
```

When called with the **trait** class, `getMethods()` returns the trait's abstract method
objects — and **on those Method objects, `@Agent` is visible**, because they were
declared with the annotation in the trait's source.

This is the part that fails if you hand it the macro impl directly or a
`java.lang.reflect.Proxy` wrapping the impl: those classes' method objects don't carry
the annotation. The trait class does.

Returns: the `java.lang.reflect.Method` object representing (e.g.)
`CreativeWriter#generateStory(String)`, with its `@Agent(outputKey="story", description="...")`
reachable via `method.getAnnotation(Agent.class)`.

### Step 4 — `AgentUtil.nonAiAgentToExecutor(impl, agentMethod)`

Also langchain4j-agentic's utility. From `AgentUtil.java:107`:

```java
public static AgentExecutor nonAiAgentToExecutor(Object agent, Method agenticMethod) {
    Agent annotation = agenticMethod.getAnnotation(Agent.class);
    String name = ...;
    String description = ...;
    String outputKey = ...;
    return new AgentExecutor(
        nonAiAgentInvoker(agent, agenticMethod, name, description, outputKey, ...),
        agent);
}
```

It reads metadata off the `@Agent` annotation, builds a `NonAiAgentInstance` carrying
that metadata + the trait's method reference + the parameter argument list (derived from
`@V` annotations or parameter names), and packages it into an `AgentExecutor` record.
The `agent` field is our macro impl; that's what `method.invoke(agent, args)` will
dispatch into at runtime.

## How the orchestrator uses it

When you call `AgenticServices.sequenceBuilder().subAgents(writer, audience, style)`,
the framework runs each argument through `agentToExecutor` (`AgentUtil.java:91`):

```java
public static AgentExecutor agentToExecutor(Object agent) {
    if (agent instanceof AgentExecutor executor) {
        return executor;          // ← short-circuit
    }
    ...
    return agent instanceof InternalAgent ...
            ? agentToExecutor(internalAgent)
            : nonAiAgentToExecutor(agent, validateAgentClass(agent.getClass()));
}
```

Because we pre-built an `AgentExecutor`, the first branch hits. **The orchestrator never
runs `validateAgentClass(agent.getClass())` on our macro impl** — which is exactly what
we want, because that call would have failed (the macro impl class doesn't carry the
annotation).

## The whole flow at runtime

```
AgenticBridge.asAgent[CreativeWriter](writerImpl)
       │
       │  ct.runtimeClass         → CreativeWriter.class            (the trait)
       │  validateAgentClass(...) → Method[generateStory], with @Agent intact
       │  nonAiAgentToExecutor(writerImpl, methodObj)
       ▼
  AgentExecutor(
    AgentInvoker( method=Method[generateStory], metadata={ name, outputKey="story", ... } ),
    agent=writerImpl                                  // the macro-generated impl
  )

…handed to subAgents(...)…
       │  agent instanceof AgentExecutor → use directly
       ▼
  Orchestrator runs. When the time comes to invoke this sub-agent:
       │  pulls "topic" from AgenticScope via @V("topic")
       │  AgentInvoker calls method.invoke(writerImpl, ["dragons and wizards"])
       │     │  method = trait method object
       │     │  writerImpl = macro impl, satisfies trait, so dispatch finds the impl's generateStory()
       │     ▼
       │  macro-emitted code runs:
       │     1. substitutes {{topic}} → "dragons and wizards" in user template
       │     2. calls Runtime.chat(model, Some(system), userText, ToolKit.empty)
       │     3. returns String  (or upickle-decoded T for typed returns)
       │  result stored in scope under outputKey="story"
       ▼
  Next sub-agent runs, reads "story", produces a new "story", …
```

## What the macro is doing under the hood

`AiServiceMacros.materializeImpl[T]` — what fires when the demo writes
`AiService.materialize[CreativeWriter](model)`:

1. **Inspect the trait** — `TypeRepr.of[T].typeSymbol.declaredMethods`, filter to
   abstract methods.
2. **For each method, extract annotations** — pull `@user(...)` and `@system(...)`'s
   string args via `argTerm.asExprOf[String].value` (handles `"a" + "b"` constant
   folding).
3. **Validate** — regex `{{name}}` placeholders out of the user template, error at
   compile time if any don't match a parameter name. This is the load-bearing thing the
   macro buys you over reflective AiServices.
4. **Synthesize the class** — `Symbol.newClass(spliceOwner, freshName, parents=[Object, T], decls=...)`
   creates an anonymous class symbol whose `decls` declare one new method per trait
   method, with `Flags.Override` and the trait method's exact `info` (same signature).
5. **Emit each method body** — `DefDef(newSym, paramRefs => …)`. Inside, fold a chain
   of `.replace("{{name}}", paramValue.toString)` calls onto the literal template (using
   `Expr` + splice), then `'{ Runtime.chat($model, $systemOpt, $userText, $kit) }`. For
   typed returns, summon `upickle.default.Reader[T]` and wrap with `read[T](_)`.
6. **Re-own** — `body.changeOwner(newSym)`. Quotes default to `Symbol.spliceOwner` (the
   materialize call site); without this re-parenting, the compiler's lambdaLift phase
   loses param refs that end up inside nested closures and you get
   `Could not find proxy for paramX`.
7. **Assemble** — `ClassDef(anonSym, parents, methodDefs)` and a `New(TypeIdent(anonSym))`
   instantiation, returned as `Expr[T]`.

So when you write:

```scala
val writer = AiService.materialize[CreativeWriter](model)
```

what the compiler actually compiles is roughly:

```scala
val writer: CreativeWriter = {
  class $anonAiService1 extends Object with CreativeWriter {
    override def generateStory(topic: String): String = {
      val userText = "Generate a draft … {{topic}}.".replace("{{topic}}", topic.toString)
      Runtime.chat(model, Some("You are a creative writer."), userText, ToolKit.empty)
    }
  }
  new $anonAiService1(): CreativeWriter
}
```

No `Proxy`, no annotation scanning at runtime, no reflective method dispatch — except
inside `AgentUtil.nonAiAgentToExecutor`, which uses reflection for the bridge layer
because the agentic orchestrator's contract is reflection-based. But that's one
`method.invoke` per agent call, not one per template variable per call.

## The piece that ties it all together

The macro and the bridge respect a strict separation:

- **Macro** owns *how a single agent method computes its result*: parameter binding,
  prompt building, model call, return-type decoding, tool dispatch. All compile-time.
- **Bridge** owns *how a Scala-instance becomes something the orchestrator recognizes*:
  trait-class lookup, `@Agent` metadata extraction, `AgentExecutor` packaging. All
  runtime.
- **Orchestrator** (unchanged langchain4j-agentic) owns *how agents compose*: scope
  wiring, sequential/parallel/conditional/loop execution, error recovery.

Neither layer crosses into the others. That's why the same `AgenticBridge.asAgent`
worked for both the sequential and parallel demos with zero modification — the bridge
has no knowledge of the orchestrator shape, only of the agent-as-Java-object interface
langchain4j-agentic publishes.
