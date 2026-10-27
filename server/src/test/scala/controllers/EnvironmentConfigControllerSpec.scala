package controllers

import controllers.common.PlayPostgresSpec
import net.wiringbits.config.{MerchantsConfig, ReCaptchaConfig}
import org.scalatest.BeforeAndAfterAll

class EnvironmentConfigControllerSpec extends PlayPostgresSpec {
  def reCaptchaConfig: ReCaptchaConfig = app.injector.instanceOf(classOf[ReCaptchaConfig])
  def merchantsConfig: MerchantsConfig = app.injector.instanceOf(classOf[MerchantsConfig])

  "GET /environment-config" should {
    "return the frontend configuration" in withApiClient { client =>
      val response = client.getEnvironmentConfig.futureValue
      response.recaptchaSiteKey must be(reCaptchaConfig.siteKey.string)
      response.maxMerchants must be(merchantsConfig.maxPerUser)
      response.maxMerchants must be(400)
    }
  }
}
