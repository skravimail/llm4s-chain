package org.l4j.template.l4j_macro

import dev.langchain4j.model.chat.ChatModel

import scala.annotation.experimental
import scala.quoted.*

/**
 * Compile-time `AiServices`-style proxy generator.
 *
 * `materialize[T](model)` (or `materialize[T](model, toolkit)`) expands at compile
 * time into a `new T { ... }` anonymous class whose method bodies call directly
 * into [[Runtime.chat]]. No `java.lang.reflect.Proxy`, no runtime annotation
 * scanning. Template placeholders are validated against parameter names at
 * compile time so `{{typo}}` becomes a compile error, not a runtime surprise.
 *
 * Typed (non-String) return types require a `upickle.default.Reader[R]` to be
 * resolvable at the macro call site — the macro summons it and emits a decode.
 */
object AiService:

  @experimental
  inline def materialize[T](inline model: ChatModel): T =
    ${ AiServiceMacros.materializeImpl[T]('model, '{ ToolKit.empty }) }

  @experimental
  inline def materialize[T](inline model: ChatModel, inline kit: ToolKit): T =
    ${ AiServiceMacros.materializeImpl[T]('model, 'kit) }

@experimental
private object AiServiceMacros:

  private val PlaceholderRegex = "\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}".r

  def materializeImpl[T: Type](
      model: Expr[ChatModel],
      kit: Expr[ToolKit],
  )(using Quotes): Expr[T] =
    import quotes.reflect.*

    val traitTpe    = TypeRepr.of[T]
    val traitSymbol = traitTpe.typeSymbol

    if !traitSymbol.flags.is(Flags.Trait) then
      report.errorAndAbort(
        s"AiService.materialize requires a trait, got ${traitSymbol.fullName}"
      )

    val abstractMethods: List[Symbol] = traitSymbol.declaredMethods.filter { m =>
      m.flags.is(Flags.Deferred) && !m.isClassConstructor
    }

    if abstractMethods.isEmpty then
      report.errorAndAbort(
        s"AiService.materialize: trait ${traitSymbol.fullName} declares no abstract methods"
      )

    // Per-method static analysis — collected once so every compile error surfaces
    // before we start emitting code.
    final case class Plan(
        sym: Symbol,
        systemMsg: Option[String],
        userTemplate: String,
        paramNames: List[String],
        returnType: TypeRepr,
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

    val plans: List[Plan] = abstractMethods.map { m =>
      val annots          = m.annotations
      val userTemplateOpt = stringArg(TypeRepr.of[user], annots, m)
      val systemMsgOpt    = stringArg(TypeRepr.of[system], annots, m)

      val userTemplate = userTemplateOpt.getOrElse {
        report.errorAndAbort(
          s"Method ${m.name} on ${traitSymbol.name} needs a @user(\"...\") annotation",
          m.pos.getOrElse(Position.ofMacroExpansion),
        )
      }

      val paramNames = m.paramSymss.flatten
        .filter(p => !p.isType)
        .map(_.name)

      val referenced = PlaceholderRegex.findAllMatchIn(userTemplate).map(_.group(1)).toList
      referenced.foreach { v =>
        if !paramNames.contains(v) then
          report.errorAndAbort(
            s"Method ${m.name}: template placeholder {{$v}} does not match any parameter " +
              s"(declared: ${paramNames.mkString(", ")})",
            m.pos.getOrElse(Position.ofMacroExpansion),
          )
      }

      val returnTpe = m.info match
        case mt: MethodType => mt.resType
        case other          => other // fallback — shouldn't happen for abstract methods

      Plan(m, systemMsgOpt, userTemplate, paramNames, returnTpe)
    }

    // Build the anonymous class skeleton: one Symbol.newMethod per planned method,
    // preserving the original signature so override-checking is happy.
    val parents = List(TypeTree.of[Object], TypeTree.of[T])

    val anonSym: Symbol = Symbol.newClass(
      parent  = Symbol.spliceOwner,
      name    = Symbol.freshName("$anonAiService"),
      parents = parents.map(_.tpe),
      decls   = (cls: Symbol) =>
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

    val anonMethods = anonSym.declaredMethods

    val methodDefs: List[DefDef] = anonMethods.zip(plans).map { case (newSym, plan) =>
      DefDef(
        newSym,
        paramRefs => {
          val termParams: List[Term] = paramRefs.flatten.collect { case t: Term => t }

          val systemExpr: Expr[Option[String]] = plan.systemMsg match
            case Some(s) => '{ Some(${ Expr(s) }) }
            case None    => '{ None }

          // Fold a `.replace("{{name}}", param.toString)` chain on the template.
          val templateExpr: Expr[String] = Expr(plan.userTemplate)
          val userTextExpr: Expr[String] = plan.paramNames.zip(termParams).foldLeft(templateExpr) {
            case (acc, (name, ref)) =>
              val placeholder: Expr[String] = Expr(s"{{$name}}")
              '{ ${ acc }.replace(${ placeholder }, ${ ref.asExpr }.toString) }
          }

          val rawCall: Expr[String] =
            '{ Runtime.chat(${ model }, ${ systemExpr }, ${ userTextExpr }, ${ kit }) }

          // Return-type-aware decode.
          val decoded: Expr[Any] =
            if plan.returnType =:= TypeRepr.of[String] then rawCall
            else
              plan.returnType.asType match
                case '[t] =>
                  Expr.summon[upickle.default.Reader[t]] match
                    case Some(rd) =>
                      '{
                        val raw: String              = $rawCall
                        val readable: ujson.Readable = raw
                        upickle.default.read[t](readable)(using $rd)
                      }
                    case None =>
                      report.errorAndAbort(
                        s"AiService.materialize: cannot decode return type ${plan.returnType.show} — " +
                          s"derive a upickle.default.Reader (e.g. `case class Foo(...) derives upickle.default.ReadWriter`)",
                        plan.sym.pos.getOrElse(Position.ofMacroExpansion),
                      )

          // Re-own the body to the synthetic method symbol: quotes default to
          // Symbol.spliceOwner (the materialize call site), which makes the
          // compiler lose track of param refs inside nested closures during
          // lambdaLift. .changeOwner re-parents every symbol in the subtree.
          Some(decoded.asTerm.changeOwner(newSym))
        },
      )
    }

    val classDef = ClassDef(anonSym, parents, methodDefs)

    val newInstance =
      Typed(
        Apply(Select(New(TypeIdent(anonSym)), anonSym.primaryConstructor), Nil),
        TypeTree.of[T],
      )

    Block(List(classDef), newInstance).asExprOf[T]
  end materializeImpl

end AiServiceMacros
