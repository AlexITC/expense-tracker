package net.wiringbits.config

import play.api.Configuration

case class TextractConfig(mode: TextractConfig.Mode)

object TextractConfig {
  sealed trait Mode
  case object Mock extends Mode
  case object Real extends Mode

  def apply(config: Configuration): TextractConfig = {
    val mode = config.get[String]("mode") match {
      case "mock" => Mock
      case "real" => Real
      case other => throw new IllegalArgumentException(s"Unknown textract.mode '$other'; expected 'mock' or 'real'")
    }
    TextractConfig(mode)
  }
}
