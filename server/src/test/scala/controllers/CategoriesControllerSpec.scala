package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.categories.{CreateCategory, UpdateCategory}
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

class CategoriesControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  "GET /categories" should {
    "return an empty list when the user has no categories" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getCategories.futureValue
      response.data must be(empty)
    }

    "return the user's categories" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createCategory(CreateCategory.Request("Groceries")).futureValue
      client.createCategory(CreateCategory.Request("Transport")).futureValue

      val response = client.getCategories.futureValue
      response.data.map(_.name) must contain theSameElementsAs List("Groceries", "Transport")
    }

    "not return another user's categories" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      val response = client2.getCategories.futureValue
      response.data must be(empty)
    }
  }

  "POST /categories" should {
    "create a category and return it" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      response.name must be("Groceries")
    }

    "fail when name is blank" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createCategory(CreateCategory.Request("  ")).expectError
      error must be("The name is required")
    }

    "fail when name exceeds 25 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createCategory(CreateCategory.Request("a" * 26)).expectError
      error must be("Name must be 25 characters or less")
    }

    "return the existing category when the same name is used again" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val second = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      second.id must be(first.id)
    }

    "return the existing category on case-insensitive name collision" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val second = client.createCategory(CreateCategory.Request("groceries")).futureValue
      second.id must be(first.id)
    }

    "allow the same name for two different users" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val response = client2.createCategory(CreateCategory.Request("Groceries")).futureValue
      response.name must be("Groceries")
    }
  }

  "PUT /categories/:id" should {
    "rename a category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      client.updateCategory(created.id, UpdateCategory.Request("Food")).futureValue
      val categories = client.getCategories.futureValue
      categories.data.map(_.name) must contain("Food")
      categories.data.map(_.name) must not contain "Groceries"
    }

    "allow renaming a category to its own name (same case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      client.updateCategory(created.id, UpdateCategory.Request("Groceries")).futureValue
    }

    "allow renaming a category to its own name (different case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      client.updateCategory(created.id, UpdateCategory.Request("groceries")).futureValue
    }

    "fail when renamed to a name used by another category of the same user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request("Food")).futureValue
      val other = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val error = client.updateCategory(other.id, UpdateCategory.Request("food")).expectError
      error must be("A category with that name already exists")
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.updateCategory(created.id, UpdateCategory.Request("Food")).expectError
      error must be("Category not found")
    }

    "fail when name is blank" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val error = client.updateCategory(created.id, UpdateCategory.Request("  ")).expectError
      error must be("The name is required")
    }

    "fail when name exceeds 25 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val error = client.updateCategory(created.id, UpdateCategory.Request("a" * 26)).expectError
      error must be("Name must be 25 characters or less")
    }
  }

  "DELETE /categories/:id" should {
    "delete a category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      client.deleteCategory(created.id).futureValue
      val categories = client.getCategories.futureValue
      categories.data must be(empty)
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createCategory(CreateCategory.Request("Groceries")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteCategory(created.id).expectError
      error must be("Category not found")
    }

    "fail when the category does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.deleteCategory(UUID.randomUUID()).expectError
      error must be("Category not found")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getCategories.expectError
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
