package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.merchants.CreateMerchant
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.config.MerchantsConfig
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import scala.concurrent.Future

class MerchantsLimitControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

  private val tokenGenerator = mock[TokenGenerator]
  private val emailApi = mock[EmailApi]
  when(emailApi.sendEmail(any[EmailRequest]())).thenReturn(Future.unit)
  private val captchaApi = mock[ReCaptchaApi]
  when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[EmailApi].to(emailApi),
        inject.bind[ReCaptchaApi].to(captchaApi),
        inject.bind[TokenGenerator].to(tokenGenerator),
        inject.bind[MerchantsConfig].toInstance(MerchantsConfig(maxPerUser = 2))
      )

  "POST /merchants" should {
    "reject creating a merchant beyond the configured limit" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createMerchant(CreateMerchant.Request("First", None, None)).futureValue
      client.createMerchant(CreateMerchant.Request("Second", None, None)).futureValue

      val error = client.createMerchant(CreateMerchant.Request("Third", None, None)).expectError
      error must be("Maximum number of merchants (2) reached")
      client.getMerchants.futureValue.data.map(_.name) must contain theSameElementsAs List("First", "Second")
    }
  }
}
