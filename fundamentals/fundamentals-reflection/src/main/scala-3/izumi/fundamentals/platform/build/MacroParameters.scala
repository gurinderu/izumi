package izumi.fundamentals.platform.build

import izumi.fundamentals.platform.language.{ScalaRelease, ScalaReleaseMacro}

import scala.quoted.{Expr, Quotes, Type}

object MacroParameters {
  import izumi.fundamentals.platform.language.IzScala
  println(IzScala.scalaRelease)
  def scalaVersion(): Option[String] = macroSetting("scala-version")
  def scalaCrossVersions(): Option[String] = macroSetting("scala-versions")

  def projectGroupId(): Option[String] = macroSetting("product-group")
  def artifactVersion(): Option[String] = macroSetting("product-version")
  def artifactName(): Option[String] = macroSetting("product-name")

  def sbtVersion(): Option[String] = macroSetting("sbt-version")
  def scalatestVersion(): Option[String] = macroSetting("scalatest-version")

  def gitRepoClean(): Option[Boolean] = macroSettingBool("git-repo-clean")
  def gitBranch(): Option[String] = macroSetting("git-branch")
  def gitHeadCommit(): Option[String] = macroSetting("git-head-commit")
  def gitDescribedVersion(): Option[String] = macroSetting("git-described-version")

  def sbtIsInsideCI(): Option[Boolean] = macroSettingBool("is-ci")

  inline def macroSetting(inline name: String): Option[String] = {
    ${ MacroParametersImpl.extractString('name) }
  }

  inline def macroSettingBool(inline name: String): Option[Boolean] = {
    ${ MacroParametersImpl.extractBool('name) }
  }

}

