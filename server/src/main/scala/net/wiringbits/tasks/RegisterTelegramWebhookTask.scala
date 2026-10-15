package net.wiringbits.tasks

import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.{TelegramConfig, WebAppConfig}
import org.slf4j.LoggerFactory

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class RegisterTelegramWebhookTask @Inject() (
    telegramConfig: TelegramConfig,
    webAppConfig: WebAppConfig,
    telegramApi: TelegramApi
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  if (telegramConfig.enabled) {
    val webhookUrl = s"${webAppConfig.host}/api/telegram/webhook"
    logger.info(s"Registering Telegram webhook: $webhookUrl")
    telegramApi.registerWebhook(webhookUrl).onComplete {
      case Success(_) => logger.info("Telegram webhook registered successfully")
      case Failure(ex) => logger.error(s"Failed to register Telegram webhook: ${ex.getMessage}", ex)
    }
  } else {
    logger.info("Telegram bot disabled, skipping webhook registration")
  }
}
