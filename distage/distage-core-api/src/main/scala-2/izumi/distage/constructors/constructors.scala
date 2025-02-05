package izumi.distage.constructors

import izumi.distage.constructors.macros.*
import izumi.distage.model.definition.dsl.ModuleDefDSL
import izumi.distage.model.exceptions.macros.{TraitInitializationFailedException, UnsupportedDefinitionException}
import izumi.distage.model.providers.Functoid
import izumi.distage.model.reflection.SafeType
import izumi.fundamentals.platform.strings.IzString.toRichIterable
import izumi.reflect.WeakTag

import scala.language.experimental.macros as enableMacros

/**
  * An implicitly summonable constructor for a type `T`, can generate constructors for:
  *
  *   - concrete classes (using [[ClassConstructor]])
  *   - traits and abstract classes ([[https://izumi.7mind.io/distage/basics.html#auto-traits Auto-Traits feature]], using [[TraitConstructor]])
  *   - "factory-like" traits and abstract classes ([[https://izumi.7mind.io/distage/basics.html#auto-factories Auto-Factories feature]], using [[FactoryConstructor]])
  *   - `zio.Has` heterogeneous map values for use by ZIO or other Reader-like effects ([[https://izumi.7mind.io/distage/basics.html#zio-has-bindings ZIO Has bindings]], using [[HasConstructor]])
  *
  * @example
  * {{{
  *  import distage.{AnyConstructor, Functoid, Injector, ModuleDef}
  *
  *  class A(val i: Int)
  *
  *  val constructor: Functoid[A] = AnyConstructor[A]
  *
  *  val lifecycle = Injector().produceGet[A](new ModuleDef {
  *    make[A].from(constructor)
  *    make[Int].fromValue(5)
  *  })
  *
  *  lifecycle.use {
  *    (a: A) =>
  *      println(a.i)
  *  }
  * }}}
  *
  * @return [[izumi.distage.model.providers.Functoid]][T] value
  */
sealed trait AnyConstructor[T] extends Any with AnyConstructorOptionalMakeDSL[T] {
  def provider: Functoid[T]
}

/**
  * An implicitly summonable constructor for a concrete class `T`
  *
  * @see [[AnyConstructor]]
  */
final class ClassConstructor[T](val provider: Functoid[T]) extends AnyVal with AnyConstructor[T]

/**
  * An implicitly summonable constructor for a traits or abstract class `T`
  *
  * @see [[https://izumi.7mind.io/distage/basics.html#auto-traits Auto-Traits feature]]
  * @see [[izumi.distage.model.definition.impl]] recommended documenting annotation for use with [[TraitConstructor]]
  * @see [[AnyConstructor]]
  */
final class TraitConstructor[T](val provider: Functoid[T]) extends AnyVal with AnyConstructor[T]

/**
  * An implicitly summonable constructor for a "factory-like" trait or abstract class `T`
  *
  * @see [[https://izumi.7mind.io/distage/basics.html#auto-factories Auto-Factories feature]]
  * @see [[izumi.distage.model.definition.impl]] recommended documenting annotation for use with [[FactoryConstructor]]
  * @see [[AnyConstructor]]
  */
final class FactoryConstructor[T](val provider: Functoid[T]) extends AnyVal with AnyConstructor[T]

/**
  * An implicitly summonable constructor for a `T <: zio.Has[A] with zio.Has[B] with zio.Has[C]`
  *
  * `zio.Has` heterogeneous map values may be used by ZIO or other Reader-like effects
  *
  * @see [[https://izumi.7mind.io/distage/basics.html#zio-has-bindings ZIO Has bindings]]
  * @see [[AnyConstructor]]
  */
final class HasConstructor[T](val provider: Functoid[T]) extends AnyVal with AnyConstructor[T]

object AnyConstructor {
  def apply[T](implicit ctor: AnyConstructor[T]): Functoid[T] = ctor.provider

  implicit def materialize[T]: AnyConstructor[T] = macro AnyConstructorMacro.mkAnyConstructor[T]
}

object ClassConstructor {
  def apply[T](implicit ctor: ClassConstructor[T]): Functoid[T] = ctor.provider

  implicit def materialize[T]: ClassConstructor[T] = macro ClassConstructorMacro.mkClassConstructor[T]
}

object TraitConstructor {
  def apply[T](implicit ctor: TraitConstructor[T]): Functoid[T] = ctor.provider

  implicit def materialize[T]: TraitConstructor[T] = macro TraitConstructorMacro.mkTraitConstructor[T]

  def wrapInitialization[A](init: => A)(implicit weakTag: WeakTag[A]): A = {
    try init
    catch {
      case e: Throwable =>
        val tpe = SafeType.unsafeGetWeak[A]
        throw new TraitInitializationFailedException(s"Failed to initialize trait $tpe. It may be an issue with the trait or a framework bug", tpe, e)
    }
  }
}

object FactoryConstructor {
  def apply[T](implicit ctor: FactoryConstructor[T]): Functoid[T] = ctor.provider

  implicit def materialize[T]: FactoryConstructor[T] = macro FactoryConstructorMacro.mkFactoryConstructor[T]
}

object HasConstructor {
  def apply[T](implicit ctor: HasConstructor[T]): Functoid[T] = ctor.provider

  val empty: HasConstructor[Any] = new HasConstructor(Functoid.unit)

  implicit def materialize[T]: HasConstructor[T] = macro HasConstructorMacro.mkHasConstructor[T]
}

private[constructors] sealed trait AnyConstructorOptionalMakeDSL[T] extends Any {
  def provider: Functoid[T]
}

object AnyConstructorOptionalMakeDSL {
  private[constructors] final class Impl[T](val provider: Functoid[T]) extends AnyVal with AnyConstructorOptionalMakeDSL[T]

  @inline def apply[T](functoid: Functoid[T]): AnyConstructorOptionalMakeDSL.Impl[T] = {
    new AnyConstructorOptionalMakeDSL.Impl[T](functoid)
  }

  def errorConstructor[T](tpe: String, nonWhitelistedMethods: List[String]): AnyConstructorOptionalMakeDSL.Impl[T] = {
    AnyConstructorOptionalMakeDSL[T](Functoid.lift(throwError(tpe, nonWhitelistedMethods)))
  }

  def throwError(tpe: String, nonWhitelistedMethods: List[String]): Nothing = {

    throw new UnsupportedDefinitionException(
      s"""`make[$tpe]` DSL failure: Called an empty error constructor, because constructor for $tpe WAS NOT generated.
         |Because after `make` there were following method calls in the same expression:${nonWhitelistedMethods.niceList()}
         |
         |These calls were assumed to be `.from`-like method calls, since they are in the white-list: ${ModuleDefDSL.MakeDSLNoOpMethodsWhitelist}
         |The assumption is that all non-whitelisted calls will eventually call any of `.from`/`.using`/`.todo` and fill in the constructor.
         |""".stripMargin
    )
  }

  implicit def materialize[T]: AnyConstructorOptionalMakeDSL.Impl[T] = macro AnyConstructorMacro.anyConstructorOptionalMakeDSL[T]
}
