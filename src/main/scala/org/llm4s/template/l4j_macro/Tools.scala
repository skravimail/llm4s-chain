package org.llm4s.template.l4j_macro

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonObjectSchema

import scala.annotation.experimental
import scala.quoted.*

/**
 * Compile-time tool harness builder.
 *
 * `Tools.from(instance)` walks `instance`'s `@tool`-annotated methods, emits a
 * langchain4j `ToolSpecification` for each (driven by the method's parameter
 * types), and emits a dispatcher that decodes the model-supplied JSON arguments
 * into typed values and invokes the method directly. No runtime reflection.
 *
 * Supported parameter types in this version: String, Int, Double, Boolean.
 * Method return types are coerced to String via `.toString` for the model.
 */
object Tools:

  @experimental
  inline def from[T](inline instance: T): ToolKit =
    ${ ToolsMacros.fromImpl[T]('instance) }

@experimental
private object ToolsMacros:

  def fromImpl[T: Type](instance: Expr[T])(using Quotes): Expr[ToolKit] =
    // Bind the instance to a single val so all dispatch lambdas close over the
    // same instance even if the user passed an expression like `new MyTool`.
    '{
      val inst: T = $instance
      ${ buildKit[T]('inst) }
    }

  private def buildKit[T: Type](instance: Expr[T])(using Quotes): Expr[ToolKit] =
    import quotes.reflect.*

    val sym = TypeRepr.of[T].typeSymbol

    val toolMethods = sym.declaredMethods.filter { m =>
      m.annotations.exists(_.tpe =:= TypeRepr.of[tool])
    }

    if toolMethods.isEmpty then
      report.errorAndAbort(
        s"Tools.from: ${sym.fullName} has no @tool-annotated methods"
      )

    final case class ParamInfo(name: String, tpe: TypeRepr, description: String)
    final case class ToolInfo(
        sym: Symbol,
        name: String,
        description: String,
        params: List[ParamInfo],
    )

    def constantStringArg(ann: Term, owner: Symbol, what: String): String =
      ann match
        case Apply(_, List(argTerm)) =>
          argTerm.asExprOf[String].value.getOrElse {
            report.errorAndAbort(
              s"@$what on ${owner.name}: argument must be a compile-time constant string, got ${argTerm.show}",
              owner.pos.getOrElse(Position.ofMacroExpansion),
            )
          }
        case other =>
          report.errorAndAbort(
            s"@$what on ${owner.name} has unexpected shape: ${other.show}",
            owner.pos.getOrElse(Position.ofMacroExpansion),
          )

    val infos: List[ToolInfo] = toolMethods.map { m =>
      val description = m.annotations.collectFirst {
        case ann if ann.tpe =:= TypeRepr.of[tool] => constantStringArg(ann, m, "tool")
      }.getOrElse(m.name)

      val params = m.paramSymss.flatten.filter(p => !p.isType).map { p =>
        val pdesc = p.annotations.collectFirst {
          case ann if ann.tpe =:= TypeRepr.of[param] => constantStringArg(ann, p, "param")
        }.getOrElse("")
        ParamInfo(p.name, p.info, pdesc)
      }

      ToolInfo(m, m.name, description, params)
    }

    // ToolSpecification (JsonObjectSchema) builder for one tool method.
    def specExpr(info: ToolInfo): Expr[ToolSpecification] =
      val builderExpr = info.params.foldLeft('{ JsonObjectSchema.builder() }) {
        case (acc, ParamInfo(pname, ptpe, pdesc)) =>
          val pnameE = Expr(pname)
          val pdescE = Expr(pdesc)
          ptpe.asType match
            case '[String]  => '{ ${ acc }.addStringProperty(${ pnameE }, ${ pdescE }) }
            case '[Int]     => '{ ${ acc }.addIntegerProperty(${ pnameE }, ${ pdescE }) }
            case '[Double]  => '{ ${ acc }.addNumberProperty(${ pnameE }, ${ pdescE }) }
            case '[Boolean] => '{ ${ acc }.addBooleanProperty(${ pnameE }, ${ pdescE }) }
            case _          =>
              report.errorAndAbort(
                s"Tool ${info.name} parameter ${pname}: unsupported type ${ptpe.show} " +
                  "(supported: String, Int, Double, Boolean)",
                info.sym.pos.getOrElse(Position.ofMacroExpansion),
              )
      }

      val requiredNames: Expr[Seq[String]] = Expr.ofSeq(info.params.map(p => Expr(p.name)))

      '{
        val schema = ${ builderExpr }.required(${ requiredNames }*).build()
        ToolSpecification.builder()
          .name(${ Expr(info.name) })
          .description(${ Expr(info.description) })
          .parameters(schema)
          .build()
      }
    end specExpr

    // Dispatcher entry for one tool method: (name, jsonArgs => result).
    def dispatchExpr(info: ToolInfo): Expr[(String, String => String)] =
      '{
        (
          ${ Expr(info.name) },
          (jsonArgs: String) =>
            val parsed: ujson.Value = ujson.read(jsonArgs)
            ${
              val parsedRef: Expr[ujson.Value] = '{ parsed }

              val argTerms: List[Term] = info.params.map { case ParamInfo(pname, ptpe, _) =>
                val pnameE = Expr(pname)
                ptpe.asType match
                  case '[String]  => '{ ${ parsedRef }.obj(${ pnameE }).str }.asTerm
                  case '[Int]     => '{ ${ parsedRef }.obj(${ pnameE }).num.toInt }.asTerm
                  case '[Double]  => '{ ${ parsedRef }.obj(${ pnameE }).num }.asTerm
                  case '[Boolean] => '{ ${ parsedRef }.obj(${ pnameE }).bool }.asTerm
                  case _          =>
                    report.errorAndAbort(
                      s"Tool ${info.name} parameter ${pname}: unsupported type ${ptpe.show}",
                      info.sym.pos.getOrElse(Position.ofMacroExpansion),
                    )
              }

              val callTerm: Term = Apply(Select(instance.asTerm, info.sym), argTerms)
              '{ ${ callTerm.asExpr }.toString }
            },
        )
      }
    end dispatchExpr

    val specsList    = Expr.ofList(infos.map(specExpr))
    val dispatchList = Expr.ofList(infos.map(dispatchExpr))

    '{
      ToolKit(${ specsList }, ${ dispatchList }.toMap)
    }
  end buildKit

end ToolsMacros
