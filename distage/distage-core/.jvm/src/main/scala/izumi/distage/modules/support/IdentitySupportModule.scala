package izumi.distage.modules.support

import izumi.distage.model.definition.ModuleDef
import izumi.distage.model.effect.{QuasiApplicative, QuasiAsync, QuasiIO, QuasiIORunner}
import izumi.functional.bio.{Clock1, Entropy1}
import izumi.fundamentals.platform.functional.Identity

object IdentitySupportModule extends IdentitySupportModule

/**
  * `Identity` effect type (aka no effect type / imperative Scala) support for `distage` resources, effects, roles & tests
  *
  * Adds [[izumi.distage.model.effect.QuasiIO]] instances to support running without an effect type in `Injector`, `distage-framework` & `distage-testkit-scalatest`
  */
trait IdentitySupportModule extends ModuleDef {
  addImplicit[QuasiApplicative[Identity]]
  addImplicit[QuasiIO[Identity]]
  addImplicit[QuasiIORunner[Identity]]
  addImplicit[QuasiAsync[Identity]]
  make[Clock1[Identity]].fromValue(Clock1.Standard)
  make[Entropy1[Identity]].fromValue(Entropy1.Standard)
}
