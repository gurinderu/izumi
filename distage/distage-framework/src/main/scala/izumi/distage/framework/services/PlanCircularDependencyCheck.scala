package izumi.distage.framework.services

import izumi.distage.framework.config.PlanningOptions
import izumi.distage.model.plan.{ExecutableOp, Plan}
import izumi.logstage.api.IzLogger

class PlanCircularDependencyCheck(
  options: PlanningOptions,
  logger: IzLogger,
) {
  def verify(plan: Plan): Unit = {
    if (options.warnOnCircularDeps) {
      val allProxies = plan.stepsUnordered.collect {
        case s: ExecutableOp.ProxyOp.MakeProxy if !s.byNameAllowed => s
      }
      allProxies.foreach {
        s =>
          val tree = s"\n${plan.renderDeps(s.target)}"
          logger.warn(s"Circular dependency has been resolved with proxy for ${s.target -> "key"}, $tree")
      }
    }
  }
}
