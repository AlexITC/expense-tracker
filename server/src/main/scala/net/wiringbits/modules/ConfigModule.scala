package net.wiringbits.modules

import com.google.inject.{AbstractModule, Provides}
import net.wiringbits.config.*
import org.slf4j.LoggerFactory
import play.api.Configuration

import javax.inject.Singleton

class ConfigModule extends AbstractModule {

  private val logger = LoggerFactory.getLogger(this.getClass)

  @Provides
  @Singleton
  def recaptchaConfig(global: Configuration): ReCaptchaConfig = {
    val config = ReCaptchaConfig(global.get[Configuration]("recaptcha"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def emailConfig(global: Configuration): EmailConfig = {
    val config = EmailConfig(global.get[Configuration]("email"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def webAppConfig(global: Configuration): WebAppConfig = {
    val config = WebAppConfig(global.get[Configuration]("webapp"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def merchantsConfig(global: Configuration): MerchantsConfig = {
    val config = MerchantsConfig(global.get[Configuration]("merchants"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def userTokensConfig(global: Configuration): UserTokensConfig = {
    val config = UserTokensConfig(global.get[Configuration]("userTokens"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def awsConfig(global: Configuration): AWSConfig = {
    val config = AWSConfig(global.get[Configuration]("aws"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def backgroundJobsExecutorConfig(global: Configuration): BackgroundJobsExecutorConfig = {
    val config = BackgroundJobsExecutorConfig(global.get[Configuration]("backgroundJobsExecutorTask"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def swaggerConfig(global: Configuration): SwaggerConfig = {
    val config = SwaggerConfig(global.get[Configuration]("swagger"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def textractConfig(global: Configuration): TextractConfig = {
    val config = TextractConfig(global.get[Configuration]("textract"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def bedrockConfig(global: Configuration): BedrockConfig = {
    val config = BedrockConfig(global.get[Configuration]("bedrock"))
    logger.info(s"Config loaded: $config")
    config
  }

  @Provides
  @Singleton
  def bdaConfig(global: Configuration): BdaConfig = {
    val config = BdaConfig(global.get[Configuration]("bda"))
    logger.info(s"Config loaded: bda.mode=${config.mode}")
    config
  }

  @Provides
  @Singleton
  def jevConfig(global: Configuration): JevConfig = {
    val config = JevConfig(global.get[Configuration]("jev"))
    logger.info(
      s"Config loaded: jev.mode=${config.mode}, baseUrl=${config.baseUrl}, model=${config.model}, " +
        s"minConfidence=${config.minConfidence}"
    )
    if (config.mode == JevConfig.Mock)
      logger.warn(
        "jev.mode is mock: statement suggestions are placeholder picks and document classification is skipped " +
          "(wrong-type uploads are not rejected), set JEV_MODE=real for real ones"
      )
    config
  }

  @Provides
  @Singleton
  def telegramConfig(global: Configuration): TelegramConfig = {
    val config = TelegramConfig(global.get[Configuration]("telegram"))
    logger.info(s"Config loaded: $config")
    config
  }
}
