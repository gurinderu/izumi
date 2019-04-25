package com.github.pshirshov.izumi.distage.roles.services

import com.github.pshirshov.izumi.distage.model.Locator
import com.github.pshirshov.izumi.distage.model.exceptions.DIException
import com.github.pshirshov.izumi.distage.roles.model.{DiAppBootstrapException, IntegrationCheck}
import com.github.pshirshov.izumi.distage.roles.services.IntegrationChecker.IntegrationCheckException
import com.github.pshirshov.izumi.fundamentals.platform.integration.ResourceCheck
import com.github.pshirshov.izumi.fundamentals.platform.strings.IzString._
import com.github.pshirshov.izumi.logstage.api.IzLogger
import distage.DIKey

import scala.util.control.NonFatal

trait IntegrationChecker {
  def check(integrationComponents: Set[DIKey], integrationLocator: Locator): Option[Seq[ResourceCheck.Failure]]

  final def checkOrFail(integrationComponents: Set[DIKey], integrationLocator: Locator): Unit = {
    check(integrationComponents, integrationLocator).fold(()) {
      failures =>
        throw new IntegrationCheckException(s"Integration check failed, failures were: ${failures.niceList()}", failures)
    }
  }
}

object IntegrationChecker {
  class IntegrationCheckException(message: String, val failures: Seq[ResourceCheck.Failure]) extends DIException(message, null)
}

class IntegrationCheckerImpl(logger: IzLogger) extends IntegrationChecker {
  def check(integrationComponents: Set[DIKey], integrationLocator: Locator): Option[Seq[ResourceCheck.Failure]] = {
    val integrations = integrationComponents.map {
      ick =>
        integrationLocator.lookup[IntegrationCheck](ick) match {
          case Some(ic) =>
            ic.value
          case None =>
            throw new DiAppBootstrapException(s"Inconsistent locator state: integration component $ick is missing from plan")
        }
    }
    failingIntegrations(integrations)
  }


  private def failingIntegrations(integrations: Set[IntegrationCheck]): Option[Seq[ResourceCheck.Failure]] = {
    logger.info(s"Going to check availability of ${integrations.size -> "resources"}")

    val failures = integrations.toSeq.flatMap {
      resource =>
        logger.debug(s"Checking $resource")
        try {
          resource.resourcesAvailable() match {
            case failure@ResourceCheck.ResourceUnavailable(reason, Some(cause)) =>
              logger.debug(s"Integration check failed, $resource unavailable: $reason, $cause")
              Some(failure)
            case failure@ResourceCheck.ResourceUnavailable(reason, None) =>
              logger.debug(s"Integration check failed, $resource unavailable: $reason")
              Some(failure)
            case ResourceCheck.Success() =>
              None
          }
        } catch {
          case NonFatal(exception) =>
            logger.error(s"Integration check for $resource threw $exception")
            Some(ResourceCheck.ResourceUnavailable(exception.getMessage, Some(exception)))
        }
    }
    Some(failures).filter(_.nonEmpty)
  }
}
