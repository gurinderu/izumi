package izumi.fundamentals.platform

trait AbstractIzPlatform {
  def platform: ScalaPlatform

  def isHeadless: Boolean

  def hasColorfulTerminal: Boolean

  def terminalColorsEnabled: Boolean

  def isGraalNativeImage: Boolean
}
