package izumi.fundamentals.platform.language



import scala.collection.immutable.{AbstractSeq, LinearSeq}
import scala.quoted.{Expr, Quotes, Type}
import scala.util.matching.Regex

object ScalaReleaseMacro {

  def doMaterialize(using Quotes): Expr[ScalaRelease] = new ScalaReleaseMacro().getScalaRelease

  private final class ScalaReleaseMacro(using qctx: Quotes) {
    import qctx.reflect._

    def getScalaRelease: Expr[ScalaRelease] = {
      ScalaRelease.parse(dotty.tools.dotc.config.Properties.versionNumberString) match {
        case ScalaRelease.`3`(minor, bugfix) =>
          '{ ScalaRelease.`3`( ${ Expr(minor)}, ${ Expr(bugfix)} )}
        case o =>
          report.errorAndAbort(s"Scala 3 expected, but something strange was extracted: $o ")
      }
    }
  }
}

object IzScala {
  inline def scalaRelease: ScalaRelease = ${ ScalaReleaseMacro.doMaterialize }
}

