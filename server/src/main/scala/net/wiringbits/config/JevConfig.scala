package net.wiringbits.config

import net.wiringbits.models.JevApiKey
import play.api.Configuration

/** JEV (Typesafe AI) config, used for the bank-statement category/merchant suggestions. */
case class JevConfig(apiKey: JevApiKey, baseUrl: String, model: String, mode: JevConfig.Mode) {
  override def toString: String = s"JevConfig(mode = $mode, baseUrl = $baseUrl, model = $model)"
}

object JevConfig {
  sealed trait Mode
  case object Mock extends Mode
  case object Real extends Mode

  def apply(config: Configuration): JevConfig = {
    val mode = config.get[String]("mode") match {
      case "mock" => Mock
      case "real" => Real
      case other => throw new IllegalArgumentException(s"Unknown jev.mode '$other'; expected 'mock' or 'real'")
    }
    JevConfig(
      apiKey = config.get[JevApiKey]("apiKey"),
      baseUrl = config.get[String]("baseUrl"),
      model = config.get[String]("model"),
      mode = mode
    )
  }
}
