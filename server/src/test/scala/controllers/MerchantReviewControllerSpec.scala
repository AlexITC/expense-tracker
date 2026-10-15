package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.merchants.{CreateMerchant, MarkMerchantAsDuplicate, ReviewMerchant, UpdateMerchant}
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import net.wiringbits.api.ApiClient
import net.wiringbits.repositories.MerchantsRepository
import sttp.client3.HttpClientFutureBackend

import java.util.UUID
import scala.concurrent.Future

class MerchantReviewControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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
        inject.bind[TokenGenerator].to(tokenGenerator)
      )

  private def buildClient() = {
    implicit val sttpBackend: sttp.client3.SttpBackend[Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }

  // ─── reviewed field ───────────────────────────────────────────────────────

  "GET /merchants reviewed field" should {
    "return reviewed=true for manually-created merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val ms = client.getMerchants.futureValue.data
      ms.must(have).size(1)
      ms.head.reviewed.must(be(true))
    }
  }

  // ─── GET /merchants/pending ───────────────────────────────────────────────

  "GET /merchants/pending" should {
    "return empty when there are no pending merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getPendingMerchants.futureValue
      response.data.must(be(empty))
    }

    "not return reviewed merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val response = client.getPendingMerchants.futureValue
      response.data.must(be(empty))
    }

    "not return another user's pending merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      val pending2 = client2.getPendingMerchants.futureValue
      pending2.data.must(be(empty))
    }
  }

  // ─── PUT /merchants/:id/review ────────────────────────────────────────────

  "PUT /merchants/:id/review" should {
    "fail for a non-existent merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val ex = client.reviewMerchant(UUID.randomUUID(), ReviewMerchant.Request(None, None)).failed.futureValue
      ex.getMessage.must(include("not found"))
    }

    "fail for an already-reviewed merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val ex = client.reviewMerchant(m.id, ReviewMerchant.Request(None, None)).failed.futureValue
      ex.getMessage.must(include("already reviewed"))
    }
  }

  // ─── POST /merchants/:id/duplicate ───────────────────────────────────────

  "POST /merchants/:id/duplicate" should {
    "fail when the pending merchant does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val real = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val ex = client
        .markMerchantAsDuplicate(UUID.randomUUID(), MarkMerchantAsDuplicate.Request(real.id))
        .failed
        .futureValue
      ex.getMessage.must(include("not found"))
    }

    "fail when the real merchant does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val real = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val ex = client
        .markMerchantAsDuplicate(real.id, MarkMerchantAsDuplicate.Request(UUID.randomUUID()))
        .failed
        .futureValue
      ex.getMessage.must(not be empty)
    }

    "fail when trying to mark a reviewed merchant as duplicate" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m1 = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val m2 = client.createMerchant(CreateMerchant.Request("Walmart", None, None, None)).futureValue

      val ex = client
        .markMerchantAsDuplicate(m1.id, MarkMerchantAsDuplicate.Request(m2.id))
        .failed
        .futureValue
      ex.getMessage.must(include("already reviewed"))
    }

    "fail when trying to mark a merchant as duplicate of itself" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val ex = client
        .markMerchantAsDuplicate(m.id, MarkMerchantAsDuplicate.Request(m.id))
        .failed
        .futureValue
      ex.getMessage.must(not be empty)
    }

    "succeed and remove the pending merchant" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val real = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val merchantsRepo = app.injector.instanceOf[MerchantsRepository]
      val pendingId = merchantsRepo.createPendingIfAbsent(loginResp.id, "AMZN").futureValue

      client.getPendingMerchants.futureValue.data.map(_.id).must(contain(pendingId))

      client.markMerchantAsDuplicate(pendingId, MarkMerchantAsDuplicate.Request(real.id)).futureValue

      client.getPendingMerchants.futureValue.data.must(be(empty))
    }
  }

  // ─── reviewed=true after update ──────────────────────────────────────────

  "PUT /merchants/:id" should {
    "preserve reviewed=true after update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      client.updateMerchant(m.id, UpdateMerchant.Request("Amazon Updated", None, None, None)).futureValue

      val ms = client.getMerchants.futureValue.data
      ms.find(_.id == m.id).map(_.reviewed).must(be(Some(true)))
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getPendingMerchants.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }
}
