package org.l4j.template.llm4s.macros

import cats.effect.IO
import org.l4j.template.llm4s.core.ChatBackend
import org.l4j.template.llm4s.memory.ChatMemory
import org.l4j.template.llm4s.memory.MemoryId
import org.l4j.template.llm4s.runtime.AiRuntime
import org.l4j.template.llm4s.runtime.RuntimeConfig
import org.l4j.template.llm4s.runtime.ToolKit
import org.l4j.template.llm4s.structured.StructuredCodec
import org.l4j.template.llm4s.structured.StructuredOutputRuntime

import scala.annotation.experimental
import scala.quoted.*

object AiService:

  @experimental
  inline def materialize[T](inline backend: ChatBackend[IO]): T =
    ${
      AiServiceMacros.materializeImpl[T](
        'backend,
        '{ ToolKit.empty[IO] },
        '{ RuntimeConfig() },
        '{ None },
        '{ None },
      )
    }

  @experimental
  inline def materialize[T](
      inline backend: ChatBackend[IO],
      inline toolKit: ToolKit[IO],
  ): T =
    ${
      AiServiceMacros.materializeImpl[T](
        'backend,
        'toolKit,
        '{ RuntimeConfig() },
        '{ None },
        '{ None },
      )
    }

  @experimental
  inline def materialize[T](
      inline backend: ChatBackend[IO],
      inline toolKit: ToolKit[IO],
      inline config: RuntimeConfig,
  ): T =
    ${ AiServiceMacros.materializeImpl[T]('backend, 'toolKit, 'config, '{ None }, '{ None }) }

  @experimental
  inline def materialize[T](
      inline backend: ChatBackend[IO],
      inline toolKit: ToolKit[IO],
      inline config: RuntimeConfig,
      inline memory: ChatMemory[IO, MemoryId],
      inline memoryId: MemoryId,
  ): T =
    ${
      AiServiceMacros.materializeImpl[T](
        'backend,
        'toolKit,
        'config,
        '{ Some(memory) },
        '{ Some(memoryId) },
      )
    }

@experimental
private object AiServiceMacros:

  private val PlaceholderRegex = "\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}".r

  def materializeImpl[T: Type](
      backend: Expr[ChatBackend[IO]],
      toolKit: Expr[ToolKit[IO]],
      config: Expr[RuntimeConfig],
      memory: Expr[Option[ChatMemory[IO, MemoryId]]],
      memoryId: Expr[Option[MemoryId]],
  )(using Quotes): Expr[T] =
    import quotes.reflect.*

    final case class Plan(
        sym: Symbol,
        systemMsg: Option[String],
        userTemplate: String,
        paramNames: List[String],
        returnType: TypeRepr,
    )

    val traitTpe    = TypeRepr.of[T]
    val traitSymbol = traitTpe.typeSymbol

    if !traitSymbol.flags.is(Flags.Trait) then
      report.errorAndAbort(
        s"AiService.materialize requires a trait, got ${traitSymbol.fullName}"
      )

    val abstractMethods = traitSymbol.declaredMethods.filter { method =>
      method.flags.is(Flags.Deferred) && !method.isClassConstructor
    }

    if abstractMethods.isEmpty then
      report.errorAndAbort(
        s"AiService.materialize: trait ${traitSymbol.fullName} declares no abstract methods"
      )

    def stringArg(annotTpe: TypeRepr, annots: List[Term], at: Symbol): Option[String] =
      annots.collectFirst {
        case ann if ann.tpe =:= annotTpe =>
          ann match
            case Apply(_, List(argTerm)) =>
              argTerm.asExprOf[String].value.getOrElse {
                report.errorAndAbort(
                  s"Annotation on ${at.name}: argument must be a compile-time constant string, got ${argTerm.show}",
                  at.pos.getOrElse(Position.ofMacroExpansion),
                )
              }
            case other =>
              report.errorAndAbort(
                s"Annotation on ${at.name} has unexpected shape: ${other.show}",
                at.pos.getOrElse(Position.ofMacroExpansion),
              )
      }

    val plans = abstractMethods.map { method =>
      val annotations     = method.annotations
      val userTemplateOpt = stringArg(TypeRepr.of[user], annotations, method)
      val systemMsgOpt    = stringArg(TypeRepr.of[system], annotations, method)

      val userTemplate = userTemplateOpt.getOrElse {
        report.errorAndAbort(
          s"Method ${method.name} on ${traitSymbol.name} needs a @user(\"...\") annotation",
          method.pos.getOrElse(Position.ofMacroExpansion),
        )
      }

      val paramNames = method.paramSymss.flatten
        .filterNot(_.isType)
        .map(_.name)

      PlaceholderRegex.findAllMatchIn(userTemplate).foreach { matched =>
        val name = matched.group(1)
        if !paramNames.contains(name) then
          report.errorAndAbort(
            s"Method ${method.name}: template placeholder {{$name}} does not match any parameter " +
              s"(declared: ${paramNames.mkString(", ")})",
            method.pos.getOrElse(Position.ofMacroExpansion),
          )
      }

      val returnType = method.info match
        case mt: MethodType => mt.resType
        case other          => other

      Plan(method, systemMsgOpt, userTemplate, paramNames, returnType)
    }

    val parents = List(TypeTree.of[Object], TypeTree.of[T])

    val anonSym = Symbol.newClass(
      parent = Symbol.spliceOwner,
      name = Symbol.freshName("$anonAiService"),
      parents = parents.map(_.tpe),
      decls = cls =>
        plans.map(plan =>
          Symbol.newMethod(
            cls,
            plan.sym.name,
            plan.sym.info,
            Flags.Override,
            Symbol.noSymbol,
          )
        ),
      selfType = None,
    )

    val methodDefs = anonSym.declaredMethods.zip(plans).map { case (newSym, plan) =>
      DefDef(
        newSym,
        paramRefs =>
          val termParams = paramRefs.flatten.collect { case t: Term => t }

          val systemExpr: Expr[Option[String]] = plan.systemMsg match
            case Some(value) => '{ Some(${ Expr(value) }) }
            case None        => '{ None }

          val userTextExpr = plan.paramNames.zip(termParams).foldLeft(Expr(plan.userTemplate)) {
            case (acc, (name, ref)) =>
              val placeholder = Expr(s"{{$name}}")
              '{ ${ acc }.replace(${ placeholder }, ${ ref.asExpr }.toString) }
          }

          val rawCall: Expr[String] =
            '{
              val runtime = AiRuntime[IO]($backend, $config)
              val effect = ($memory, $memoryId) match
                case (Some(mem), Some(id)) =>
                  runtime.chatWithMemory(mem, id, $systemExpr, $userTextExpr, $toolKit)
                case _ =>
                  runtime.chat($systemExpr, $userTextExpr, $toolKit)
              effect.unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
            }

          val decoded: Expr[Any] =
            if plan.returnType =:= TypeRepr.of[String] then rawCall
            else
              plan.returnType.asType match
                case '[t] =>
                  Expr.summon[StructuredCodec[t]] match
                    case Some(codec) =>
                      '{
                        val effect = ($memory, $memoryId) match
                          case (Some(mem), Some(id)) =>
                            StructuredOutputRuntime.chatWithMemory[IO, t, MemoryId](
                              backend = $backend,
                              config = $config,
                              memory = mem,
                              memoryId = id,
                              system = $systemExpr,
                              userText = $userTextExpr,
                              toolKit = $toolKit,
                            )(using cats.effect.IO.asyncForIO, $codec)
                          case _ =>
                            StructuredOutputRuntime.chat[IO, t](
                              backend = $backend,
                              config = $config,
                              system = $systemExpr,
                              userText = $userTextExpr,
                              toolKit = $toolKit,
                            )(using cats.effect.IO.asyncForIO, $codec)
                        effect
                          .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
                      }
                    case None =>
                      Expr.summon[upickle.default.Reader[t]] match
                        case Some(reader) =>
                          '{
                            val raw: String              = $rawCall
                            val readable: ujson.Readable = raw
                            upickle.default.read[t](readable)(using $reader)
                          }
                        case None =>
                          report.errorAndAbort(
                            s"AiService.materialize: cannot decode return type ${plan.returnType.show} — " +
                              "derive a StructuredCodec or upickle Reader/ReadWriter for that type",
                            plan.sym.pos.getOrElse(Position.ofMacroExpansion),
                          )

          Some(decoded.asTerm.changeOwner(newSym))
      )
    }

    val classDef = ClassDef(anonSym, parents, methodDefs)
    val newInstance =
      Typed(
        Apply(Select(New(TypeIdent(anonSym)), anonSym.primaryConstructor), Nil),
        TypeTree.of[T],
      )

    Block(List(classDef), newInstance).asExprOf[T]
