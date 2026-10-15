package net.wiringbits.actions.environmentconfig

import net.wiringbits.api.models.environmentconfig.GetEnvironmentConfig
import net.wiringbits.config.{ReCaptchaConfig, TelegramConfig}

import javax.inject.Inject
import scala.concurrent.Future

class GetEnvironmentConfigAction @Inject() (
    reCaptchaConfig: ReCaptchaConfig,
    telegramConfig: TelegramConfig
)() {
  def apply(): Future[GetEnvironmentConfig.Response] = Future.successful {
    GetEnvironmentConfig.Response(
      recaptchaSiteKey = reCaptchaConfig.siteKey.string,
      telegramBotName = if telegramConfig.enabled then Some(telegramConfig.botName) else None
    )
  }
}
