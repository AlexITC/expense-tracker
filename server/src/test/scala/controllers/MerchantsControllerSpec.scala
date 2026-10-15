package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.merchants.{CreateMerchant, UpdateMerchant}
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

import java.util.UUID
import scala.concurrent.Future

class MerchantsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  "GET /merchants" should {
    "return an empty list when the user has no merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getMerchants.futureValue
      response.data must be(empty)
    }

    "return the user's merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue
      client.createMerchant(CreateMerchant.Request("Walmart", Some("Nearby store"), None)).futureValue

      val response = client.getMerchants.futureValue
      response.data.map(_.name) must contain theSameElementsAs List("Amazon", "Walmart")
    }

    "not return another user's merchants" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      val response = client2.getMerchants.futureValue
      response.data must be(empty)
    }
  }

  "POST /merchants" should {
    "create a merchant without notes" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue
      response.name must be("Amazon")
    }

    "create a merchant with notes" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.createMerchant(CreateMerchant.Request("Amazon", Some("Prime member"), None)).futureValue
      response.name must be("Amazon")
    }

    "fail when name is blank" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createMerchant(CreateMerchant.Request("  ", None, None)).expectError
      error must be("The name is required")
    }

    "fail when name exceeds 100 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createMerchant(CreateMerchant.Request("a" * 101, None, None)).expectError
      error must be("Name must be 100 characters or less")
    }

    "fail when notes exceed 255 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createMerchant(CreateMerchant.Request("Amazon", Some("a" * 256), None)).expectError
      error must be("Notes must be 255 characters or less")
    }

    "fail when a merchant with the same name already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).expectError
      error must be("A merchant with that name already exists")
    }

    "fail on case-insensitive name collision" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.createMerchant(CreateMerchant.Request("amazon", None, None)).expectError
      error must be("A merchant with that name already exists")
    }

    "allow the same name for two different users" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val response = client2.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue
      response.name must be("Amazon")
    }
  }

  "PUT /merchants/:id" should {
    "rename a merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon US", None, None)).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data.map(_.name) must contain("Amazon US")
      merchants.data.map(_.name) must not contain "Amazon"
    }

    "update notes" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", Some("Prime member"), None)).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data.find(_.id == created.id).get.notes must be(Some("Prime member"))
    }

    "clear notes to null" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", Some("old note"), None)).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", None, None)).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data.find(_.id == created.id).get.notes must be(None)
    }

    "allow renaming to own name (same case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", None, None)).futureValue
    }

    "allow renaming to own name (different case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("amazon", None, None)).futureValue
    }

    "fail when renamed to a name used by another merchant of the same user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createMerchant(CreateMerchant.Request("Walmart", None, None)).futureValue
      val other = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.updateMerchant(other.id, UpdateMerchant.Request("walmart", None, None)).expectError
      error must be("A merchant with that name already exists")
    }

    "fail when the merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.updateMerchant(created.id, UpdateMerchant.Request("Amazon US", None, None)).expectError
      error must be("Merchant not found")
    }

    "fail when name is blank" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.updateMerchant(created.id, UpdateMerchant.Request("  ", None, None)).expectError
      error must be("The name is required")
    }

    "fail when name exceeds 100 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.updateMerchant(created.id, UpdateMerchant.Request("a" * 101, None, None)).expectError
      error must be("Name must be 100 characters or less")
    }

    "fail when notes exceed 255 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val error = client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", Some("a" * 256), None)).expectError
      error must be("Notes must be 255 characters or less")
    }
  }

  "merchant default category" should {
    "create a merchant with a default category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, Some(cat.id))).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data.find(_.id == created.id).get.categoryId must be(Some(cat.id))
    }

    "set and clear a default category via update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", None, Some(cat.id))).futureValue
      val withCat = client.getMerchants.futureValue
      withCat.data.find(_.id == created.id).get.categoryId must be(Some(cat.id))

      client.updateMerchant(created.id, UpdateMerchant.Request("Amazon", None, None)).futureValue
      val cleared = client.getMerchants.futureValue
      cleared.data.find(_.id == created.id).get.categoryId must be(None)
    }

    "fail to create with a category belonging to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.createMerchant(CreateMerchant.Request("Amazon", None, Some(cat.id))).expectError
      error must be("Category not found")
    }

    "fail to update with a category belonging to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val created = client2.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue
      val error = client2.updateMerchant(created.id, UpdateMerchant.Request("Amazon", None, Some(cat.id))).expectError
      error must be("Category not found")
    }

    "clear merchant category_id when the category is deleted" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, Some(cat.id))).futureValue

      client.deleteCategory(cat.id).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data.find(_.id == created.id).get.categoryId must be(None)
    }
  }

  "DELETE /merchants/:id" should {
    "delete a merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      client.deleteMerchant(created.id).futureValue
      val merchants = client.getMerchants.futureValue
      merchants.data must be(empty)
    }

    "fail when the merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteMerchant(created.id).expectError
      error must be("Merchant not found")
    }

    "fail when the merchant does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.deleteMerchant(UUID.randomUUID()).expectError
      error must be("Merchant not found")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getMerchants.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }
}
