package izumi.distage.constructors.macros

import izumi.distage.model.providers.Functoid
import izumi.distage.model.reflection.Provider.ProviderType
import izumi.distage.model.reflection.macros.DIUniverseLiftables
import izumi.distage.model.reflection.universe.StaticDIUniverse
import izumi.distage.model.reflection.{Provider, ReflectionProvider}
import izumi.fundamentals.reflection.ReflectionUtil

import scala.annotation.{nowarn, tailrec}
import scala.reflect.macros.blackbox

abstract class ClassConstructorMacros extends ConstructorMacrosBase {
  import c.universe._

  def mkClassConstructorProvider[T: c.WeakTypeTag](reflectionProvider: ReflectionProvider.Aux[u.type])(targetType: Type): c.Expr[Functoid[T]] = {
    val associations = reflectionProvider.constructorParameterLists(targetType)
    generateProvider[T, ProviderType.Class.type](associations)(args => q"new $targetType(...$args)")
  }
}
object ClassConstructorMacros {
  type Aux[C <: blackbox.Context, U <: StaticDIUniverse] = ClassConstructorMacros { val c: C; val u: U }
  def apply(c0: blackbox.Context)(u0: StaticDIUniverse.Aux[c0.universe.type]): ClassConstructorMacros.Aux[c0.type, u0.type] = {
    new ClassConstructorMacros {
      val c: c0.type = c0
      val u: u0.type = u0
    }
  }
}

abstract class HasConstructorMacros extends ConstructorMacrosBase {
  import c.universe._

  def ziohasConstructorAssertion(targetType: Type, deepIntersection: List[Type]): Unit = {
    val (good, bad) = deepIntersection.partition(tpe => tpe.typeConstructor.typeSymbol.fullName == "zio.Has")
    if (bad.nonEmpty) {
      c.abort(
        c.enclosingPosition,
        s"Cannot construct an implementation for ZIO Has type `$targetType`: intersection contains type constructors that aren't `zio.Has` or `Any`: $bad (${bad.map(_.typeSymbol)})",
      )
    }
    if (good.isEmpty) {
      c.abort(
        c.enclosingPosition,
        s"Cannot construct an implementation for ZIO Has type `$targetType`: the intersection type is empty, it contains no `zio.Has` or `Any` type constructors in it, type was $targetType (${targetType.typeSymbol}",
      )
    }
  }

}
object HasConstructorMacros {
  type Aux[C <: blackbox.Context, U <: StaticDIUniverse] = HasConstructorMacros { val c: C; val u: U }
  def apply(c0: blackbox.Context)(u0: StaticDIUniverse.Aux[c0.universe.type]): HasConstructorMacros.Aux[c0.type, u0.type] = {
    new HasConstructorMacros {
      val c: c0.type = c0
      val u: u0.type = u0
    }
  }
}

abstract class TraitConstructorMacros extends ConstructorMacrosBase {
  import c.universe._

  def mkTraitConstructorProvider[T: c.WeakTypeTag](wiring: u.Wiring.SingletonWiring.Trait): c.Expr[Functoid[T]] = {
    val u.Wiring.SingletonWiring.Trait(targetType, classParameters, methods, _) = wiring
    val traitParameters = methods.map(_.asParameter)

    generateProvider[T, ProviderType.Trait.type](classParameters :+ traitParameters) {
      argss =>
        q"_root_.izumi.distage.constructors.TraitConstructor.wrapInitialization[$targetType](${val methodDefs = methods.zip(argss.last).map {
            case (method, paramSeqIndexTree) => method.traitMethodExpr(paramSeqIndexTree)
          }
          mkNewAbstractTypeInstanceApplyExpr(targetType, argss.init, methodDefs) })"
    }
  }

  def traitConstructorAssertion(targetType: Type): Unit = {
    ReflectionUtil
      .deepIntersectionTypeMembers[c.universe.type](targetType)
      .find(tpe => tpe.typeSymbol.isParameter || tpe.typeSymbol.isFinal)
      .foreach {
        err =>
          c.abort(
            c.enclosingPosition,
            s"Cannot construct an implementation for $targetType: it contains a type parameter or a final class $err (${err.typeSymbol}) in type constructor position",
          )
      }
  }

  def symbolToTrait(reflectionProvider: ReflectionProvider.Aux[u.type])(targetType: Type): u.Wiring.SingletonWiring.Trait = {
    reflectionProvider.symbolToWiring(targetType) match {
      case trait0: u.Wiring.SingletonWiring.Trait => trait0
      case wiring => throw new RuntimeException(s"""Tried to create a `TraitConstructor[$targetType]`, but `$targetType` is not a trait or an abstract class!
                                                   |
                                                   |Inferred wiring is: $wiring""".stripMargin)
    }
  }
}
object TraitConstructorMacros {
  type Aux[C <: blackbox.Context, U <: StaticDIUniverse] = TraitConstructorMacros { val c: C; val u: U }
  def apply(c0: blackbox.Context)(u0: StaticDIUniverse.Aux[c0.universe.type]): TraitConstructorMacros.Aux[c0.type, u0.type] = {
    new TraitConstructorMacros {
      val c: c0.type = c0
      val u: u0.type = u0
    }
  }
}

abstract class FactoryConstructorMacros extends ConstructorMacrosBase {
  import c.universe._

  def generateFactoryMethod(dependencyArgMap: Map[u.DIKey.BasicKey, c.Tree])(factoryMethod0: u.Wiring.Factory.FactoryMethod): c.Tree = {
    val u.Wiring.Factory.FactoryMethod(factoryMethod, productConstructor, _) = factoryMethod0

    val (methodArgListDecls, methodArgList) = {
      @nowarn("msg=outer reference")
      @tailrec def instantiatedMethod(tpe: Type): MethodTypeApi = {
        tpe match {
          case m: MethodTypeApi => m
          case p: PolyTypeApi => instantiatedMethod(p.resultType)
          case _ => throw new RuntimeException(s"Impossible case, a method type that is neither a `MethodType` nor a `PolyType` - $tpe (class: ${tpe.getClass})")
        }
      }
      val paramLists = instantiatedMethod(factoryMethod.typeSignatureInDefiningClass).paramLists.map(_.map {
        argSymbol =>
          val tpe = argSymbol.typeSignature
          val name = argSymbol.asTerm.name
          val expr = if (argSymbol.isImplicit) q"implicit val $name: $tpe" else q"val $name: $tpe"
          expr -> (tpe -> name)
      })
      paramLists.map(_.map(_._1)) -> paramLists.flatten.map(_._2)
    }

    val typeParams: List[TypeDef] = factoryMethod.underlying.asMethod.typeParams.map(c.internal.typeDef(_))

    val (associations, fnTree) = mkAnyConstructorFunction(productConstructor)
    val args = associations.map {
      param =>
        val candidates = methodArgList.collect {
          case (tpe, termName) if ReflectionUtil.stripByName(u.u)(tpe) =:= param.nonBynameTpe =>
            Liftable.liftName(termName)
        }
        candidates match {
          case one :: Nil =>
            one
          case Nil =>
            dependencyArgMap.getOrElse(
              param.key,
              c.abort(
                c.enclosingPosition,
                s"Couldn't find a dependency to satisfy parameter ${param.name}: ${param.tpe} in factoryArgs: ${dependencyArgMap.keys
                    .map(_.tpe)}, methodArgs: ${methodArgList.map(_._1)}",
              ),
            )
          case multiple =>
            multiple
              .find(_.toString == param.name)
              .getOrElse(
                c.abort(
                  c.enclosingPosition,
                  s"""Couldn't disambiguate between multiple arguments with the same type available for parameter ${param.name}: ${param.tpe} of ${factoryMethod.finalResultType} constructor
                     |Expected one of the arguments to be named `${param.name}` or for the type to be unique among factory method arguments""".stripMargin,
                )
              )
        }
    }
    val freshName = TermName(c.freshName("wiring"))

    q"""
      final def ${TermName(factoryMethod.name)}[..$typeParams](...$methodArgListDecls): ${factoryMethod.finalResultType} = {
        val $freshName = $fnTree
        $freshName(..$args)
      }
      """
  }

  def mkAnyConstructorFunction(wiring: u.Wiring.SingletonWiring): (List[u.Association.Parameter], Tree) = {
    wiring match {
      case w: u.Wiring.SingletonWiring.Class =>
        mkClassConstructorFunction(w)
      case w: u.Wiring.SingletonWiring.Trait =>
        mkTraitConstructorFunction(w)
    }
  }

  def mkClassConstructorFunction(w: u.Wiring.SingletonWiring.Class): (List[u.Association.Parameter], Tree) = {
    val u.Wiring.SingletonWiring.Class(targetType, classParameters, _) = w
    val (associations, ctorArgs, ctorArgNamesLists) = CtorArgument.unzipLists(classParameters.map(_.map(mkCtorArgument(_))))
    (associations, q"(..$ctorArgs) => new $targetType(...$ctorArgNamesLists)")
  }

  def mkTraitConstructorFunction(wiring: u.Wiring.SingletonWiring.Trait): (List[u.Association.Parameter], Tree) = {
    val u.Wiring.SingletonWiring.Trait(targetType, classParameters, methods, _) = wiring

    val (ctorAssociations, classCtorArgs, ctorParams) = CtorArgument.unzipLists(classParameters.map(_.map(mkCtorArgument(_))))
    val (traitAssociations, traitCtorArgs, wireMethods) = methods.map(mkCtorArgument(_)).unzip3(CtorArgument.asTraitMethod)

    (
      ctorAssociations ++ traitAssociations, {
        val newExpr = mkNewAbstractTypeInstanceApplyExpr(targetType, ctorParams, wireMethods)
        q"(..${classCtorArgs ++ traitCtorArgs}) => _root_.izumi.distage.constructors.TraitConstructor.wrapInitialization[$targetType]($newExpr)"
      },
    )
  }

  def symbolToFactory(reflectionProvider: ReflectionProvider.Aux[u.type])(targetType: Type): u.Wiring.Factory = {
    reflectionProvider.symbolToWiring(targetType) match {
      case factory: u.Wiring.Factory => factory
      case wiring => throw new RuntimeException(s"""Tried to create a `FactoryConstructor[$targetType]`, but `$targetType` is not a factory!
                                                   |
                                                   |Inferred wiring is: $wiring""".stripMargin)
    }
  }
}
object FactoryConstructorMacros {
  type Aux[C <: blackbox.Context, U <: StaticDIUniverse] = FactoryConstructorMacros { val c: C; val u: U }
  def apply(c0: blackbox.Context)(u0: StaticDIUniverse.Aux[c0.universe.type]): FactoryConstructorMacros.Aux[c0.type, u0.type] = {
    new FactoryConstructorMacros {
      val c: c0.type = c0
      val u: u0.type = u0
    }
  }
}

abstract class ConstructorMacrosBase {

  val c: blackbox.Context
  val u: StaticDIUniverse.Aux[c.universe.type]

  import c.universe._

  def mkCtorArgument(association: u.Association): CtorArgument = {
    val (argName, freshArgName) = association.ctorArgumentExpr(c)
    CtorArgument(association.asParameter, argName, freshArgName)
  }

  case class CtorArgument(parameter: u.Association.Parameter, ctorArgument: Tree, ctorArgumentName: Tree) {
    def traitMethodExpr: Tree = parameter.traitMethodExpr(ctorArgumentName)
  }
  object CtorArgument {
    def asCtorArgument: CtorArgument => (u.Association.Parameter, Tree, Tree) = {
      case CtorArgument(parameter, ctorArgument, ctorArgumentName) => (parameter, ctorArgument, ctorArgumentName)
    }
    def asTraitMethod(c: CtorArgument): (u.Association.Parameter, Tree, Tree) = (c.parameter, c.ctorArgument, c.traitMethodExpr)

    def unzipLists(ls: List[List[CtorArgument]]): (List[u.Association.Parameter], List[Tree], List[List[Tree]]) = {
      val (associations, ctorArgs) = ls.flatten.map {
        case CtorArgument(p, a, _) => (p, a)
      }.unzip
      val ctorArgNamesLists = ls.map(_.map(_.ctorArgumentName))
      (associations, ctorArgs, ctorArgNamesLists)
    }
  }

  def mkNewAbstractTypeInstanceApplyExpr(
    targetType: Type,
    constructorParameters: List[List[Tree]],
    methodImpls: List[Tree],
  ): Tree = {
    val parents = ReflectionUtil.deepIntersectionTypeMembers[u.u.type](targetType)
    parents match {
      case parent :: Nil if parent.typeSymbol.isClass && !parent.typeSymbol.asClass.isTrait =>
        if (methodImpls.isEmpty) {
          q"new $parent(...$constructorParameters) {}"
        } else {
          q"new $parent(...$constructorParameters) { ..$methodImpls }"
        }
      case _ =>
        if (constructorParameters.nonEmpty) {
          val (classes, traits) = parents.partition(t => t.typeSymbol.isClass && !t.typeSymbol.asClass.isTrait)
          classes match {
            case singlePrimaryClass :: Nil =>
              q"new $singlePrimaryClass(...$constructorParameters) with ..$traits { ..$methodImpls }"
            case Nil =>
              val primaryClassFromBaseClasses = parents.collectFirst(scala.Function.unlift {
                p => p.baseClasses.find(b => (b ne p.typeSymbol) && b.isClass && !b.asClass.isTrait)
              })
              primaryClassFromBaseClasses match {
                case Some(firstFoundCtorClass) =>
                  q"new $firstFoundCtorClass(...$constructorParameters) with ..$traits { ..$methodImpls }"
                case None =>
                  c.abort(
                    c.enclosingPosition,
                    s"""Unsupported case: an odd type containing constructor parameters but couldn't find a class ancestor to apply the parameters to?..
                       |Please manually create the provided abstract class with the added traits.
                       |When trying to create a TraitConstructor for $targetType""".stripMargin,
                  )
              }
            case classes =>
              c.abort(
                c.enclosingPosition,
                s"""Unsupported case: intersection type containing multiple abstract class ancestors.
                   |classes=$classes
                   |Please manually create the provided abstract class with the added traits.
                   |When trying to create a TraitConstructor for $targetType""".stripMargin,
              )
          }
        } else {
          if (methodImpls.isEmpty) {
            q"new ..$parents {}"
          } else {
            q"new ..$parents { ..$methodImpls }"
          }
        }
    }
  }

  def generateProvider[T: c.WeakTypeTag, P <: ProviderType with Singleton: c.WeakTypeTag](
    parameters: List[List[u.Association.Parameter]]
  )(fun: List[List[Tree]] => Tree
  ): c.Expr[Functoid[T]] = {
    val tools = DIUniverseLiftables(u)
    import tools.{liftTypeToSafeType, liftableParameter}

    val seqName = if (parameters.exists(_.nonEmpty)) TermName(c.freshName("seqAny")) else TermName("_")

    val casts = {
      var i = 0
      parameters.map(_.map {
        param =>
          val seqCast = if (param.isByName) {
            q"$seqName($i).asInstanceOf[() => ${param.nonBynameTpe}].apply()"
          } else {
            q"$seqName($i).asInstanceOf[${param.nonBynameTpe}]"
          }

          i += 1
          seqCast
      })
    }

    c.Expr[Functoid[T]] {
      q"""{
          new ${weakTypeOf[Functoid[T]]}(
            new ${weakTypeOf[Provider.ProviderImpl[T]]}(
              ${Liftable.liftList.apply(parameters.flatten)},
              ${liftTypeToSafeType(weakTypeOf[T])},
              { ($seqName: _root_.scala.Seq[_root_.scala.Any]) => ${fun(casts)}: ${weakTypeOf[T]} },
             ${symbolOf[P].asClass.module},
            )
          )
        }"""
    }
  }

}
