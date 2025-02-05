package izumi.distage.provisioning.strategies

import izumi.distage.model.exceptions.planning.DIBugException
import izumi.distage.model.plan.ExecutableOp.WiringOp
import izumi.distage.model.provisioning.strategies.ProviderStrategy
import izumi.distage.model.provisioning.{NewObjectOp, ProvisioningKeyProvider}
import izumi.distage.model.reflection.TypedRef

class ProviderStrategyDefaultImpl extends ProviderStrategy {
  def callProvider(context: ProvisioningKeyProvider, op: WiringOp.CallProvider): Seq[NewObjectOp.NewInstance] = {
    val args: Vector[TypedRef[?]] = op.wiring.associations.map {
      param =>
        context.fetchKey(param.key, param.isByName) match {
          case Some(dep) =>
            TypedRef(dep, param.key.tpe, param.isByName)
          case _ =>
            throw new DIBugException(
              "The impossible happened! Tried to instantiate class," +
              s" but the dependency has not been initialized: Class: ${op.target}, dependency: $param"
            )
        }
    }.toVector

    val instance = op.wiring.provider.unsafeApply(args)
    Seq(NewObjectOp.NewInstance(op.target, op.instanceType, instance))
  }
}
