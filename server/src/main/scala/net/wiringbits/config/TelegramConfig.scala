package net.wiringbits.config

import play.api.Configuration

case class TelegramConfig(
    enabled: Boolean,
    botToken: String,
    webhookSecret: String,
    botName: String,
    webAppDevMode: Boolean = false
) {
  override def toString: String = s"TelegramConfig(enabled=$enabled, botName=$botName)"
}

object TelegramConfig {
  def apply(config: Configuration): TelegramConfig =
    TelegramConfig(
      enabled = config.get[Boolean]("enabled"),
      botToken = config.get[String]("botToken"),
      webhookSecret = config.get[String]("webhookSecret"),
      botName = config.get[String]("botName"),
      webAppDevMode = config.getOptional[Boolean]("webAppDevMode").getOrElse(false)
    )
}
