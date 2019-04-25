package com.github.pshirshov.izumi.distage

import com.github.pshirshov.izumi.distage.model._
import com.github.pshirshov.izumi.distage.model.definition.DIResource.DIResourceBase
import com.github.pshirshov.izumi.distage.model.monadic.DIEffect
import com.github.pshirshov.izumi.distage.model.plan.{AbstractPlan, OrderedPlan, SemiPlan}
import com.github.pshirshov.izumi.distage.model.provisioning.PlanInterpreter
import com.github.pshirshov.izumi.distage.model.provisioning.PlanInterpreter.{FailedProvision, FinalizersFilter}
import com.github.pshirshov.izumi.distage.model.reflection.universe.RuntimeDIUniverse.TagK

class InjectorDefaultImpl(parentContext: Locator) extends Injector {
  override def plan(input: PlannerInput): OrderedPlan = {
    parentContext.get[Planner].plan(input)
  }

  override def finish(semiPlan: SemiPlan): OrderedPlan = {
    parentContext.get[Planner].finish(semiPlan)
  }

  override def merge(a: AbstractPlan, b: AbstractPlan): OrderedPlan = {
    parentContext.get[Planner].merge(a, b)
  }


  override protected[distage] def produceFX[F[_] : TagK : DIEffect](plan: OrderedPlan, filter: FinalizersFilter[F]): DIResourceBase[F, Locator] = {
    produceDetailedFX[F](plan, filter).evalMap(_.throwOnFailure())
  }


  override protected[distage] def produceDetailedFX[F[_] : TagK : DIEffect](plan: OrderedPlan, filter: FinalizersFilter[F]): DIResourceBase[F, Either[FailedProvision[F], Locator]] = {
    parentContext.get[PlanInterpreter].instantiate[F](plan, parentContext, filter)
  }
}
