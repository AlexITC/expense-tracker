package controllers
import net.wiringbits.common.models.CategoryDescription
import net.wiringbits.common.models.CategoryName

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
import play.api.libs.json.Json
import play.api.test.Helpers.*
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

      client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue
      client.createCategory(CreateCategory.Request(CategoryName.trusted("Transport"), description = None)).futureValue

      val response = client.getCategories.futureValue
      response.data.map(_.name) must contain theSameElementsAs List("Groceries", "Transport")
    }

    "not return another user's categories" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      val response = client2.getCategories.futureValue
      response.data must be(empty)
    }

    "not expose a category description to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client
        .createCategory(
          CreateCategory.Request(
            CategoryName.trusted("Groceries"),
            description = Some(CategoryDescription.trusted("Supermarket purchases"))
          )
        )
        .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      client2.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val response = client2.getCategories.futureValue
      response.data.map(_.description) must be(List(None))
    }
  }

  "POST /categories" should {
    "create a category and return it" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue
      response.name must be("Groceries")
    }

    "return the existing category when the same name is used again" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val second =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue
      second.id must be(first.id)
    }

    "return the existing category on case-insensitive name collision" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val second =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("groceries"), description = None)).futureValue
      second.id must be(first.id)
      second.name must be("Groceries")
    }

    "allow the same name for two different users" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val response = client2
        .createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None))
        .futureValue
      response.name must be("Groceries")
    }

    "store the description" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client
        .createCategory(
          CreateCategory.Request(
            CategoryName.trusted("Groceries"),
            description = Some(CategoryDescription.trusted("Supermarket purchases"))
          )
        )
        .futureValue

      val categories = client.getCategories.futureValue
      categories.data.map(_.description) must be(List(Some("Supermarket purchases")))
    }

    "fail and keep the existing description when the name already exists with a different one" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
          )
          .futureValue

        val error =
          client
            .createCategory(
              CreateCategory.Request(
                CategoryName.trusted("groceries"),
                description = Some(CategoryDescription.trusted("Supermarket"))
              )
            )
            .expectError
        error must be("A category with that name already exists; edit it to change its description")
        client.getCategories.futureValue.data.map(_.description) must be(List(Some("Food")))
    }

    "fail when a description is given for an existing name that has none" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val error =
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
          )
          .expectError
      error must be("A category with that name already exists; edit it to change its description")
      client.getCategories.futureValue.data.map(_.description) must be(List(None))
    }

    "return the existing category when the name already exists and no different description is given" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        val first =
          client
            .createCategory(
              CreateCategory
                .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
            )
            .futureValue

        client
          .createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None))
          .futureValue
          .id must be(first.id)
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted(" Food ")))
          )
          .futureValue
          .id must be(first.id)
        client.getCategories.futureValue.data.map(_.description) must be(List(Some("Food")))
    }

    "store a blank description as none" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client
        .createCategory(
          CreateCategory
            .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("   ")))
        )
        .futureValue

      val categories = client.getCategories.futureValue
      categories.data.map(_.description) must be(List(None))
    }

    "accept a 500 character description surrounded by spaces and trim it" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val description = "a" * 500
      client
        .createCategory(
          CreateCategory.Request(
            CategoryName.trusted("Groceries"),
            description = Some(CategoryDescription.trusted(s"  $description  "))
          )
        )
        .futureValue

      val categories = client.getCategories.futureValue
      categories.data.map(_.description) must be(List(Some(description)))
    }
  }

  "PUT /categories/:id" should {
    "rename a category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      client
        .updateCategory(created.id, UpdateCategory.Request(CategoryName.trusted("Food"), description = None))
        .futureValue
      val categories = client.getCategories.futureValue
      categories.data.map(_.name) must contain("Food")
      categories.data.map(_.name) must not contain "Groceries"
    }

    "allow renaming a category to its own name (same case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      client
        .updateCategory(created.id, UpdateCategory.Request(CategoryName.trusted("Groceries"), description = None))
        .futureValue
    }

    "allow renaming a category to its own name (different case)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      client
        .updateCategory(created.id, UpdateCategory.Request(CategoryName.trusted("groceries"), description = None))
        .futureValue
    }

    "fail when renamed to a name used by another category of the same user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createCategory(CreateCategory.Request(CategoryName.trusted("Food"), description = None)).futureValue
      val other =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val error = client
        .updateCategory(other.id, UpdateCategory.Request(CategoryName.trusted("food"), description = None))
        .expectError
      error must be("A category with that name already exists")
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2
        .updateCategory(created.id, UpdateCategory.Request(CategoryName.trusted("Food"), description = None))
        .expectError
      error must be("Category not found")
    }

    "set the description" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      client
        .updateCategory(
          created.id,
          UpdateCategory
            .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
        )
        .futureValue
      client.getCategories.futureValue.data.map(_.description) must be(List(Some("Food")))
    }

    "edit the description" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
          )
          .futureValue

      client
        .updateCategory(
          created.id,
          UpdateCategory
            .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Supermarket")))
        )
        .futureValue
      client.getCategories.futureValue.data.map(_.description) must be(List(Some("Supermarket")))
    }

    "clear the description when none is given" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
          )
          .futureValue

      client
        .updateCategory(created.id, UpdateCategory.Request(CategoryName.trusted("Groceries"), description = None))
        .futureValue
      client.getCategories.futureValue.data.map(_.description) must be(List(None))
    }

    "clear the description when a blank one is given" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client
          .createCategory(
            CreateCategory
              .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("Food")))
          )
          .futureValue

      client
        .updateCategory(
          created.id,
          UpdateCategory
            .Request(CategoryName.trusted("Groceries"), description = Some(CategoryDescription.trusted("  ")))
        )
        .futureValue
      client.getCategories.futureValue.data.map(_.description) must be(List(None))
    }

  }

  "DELETE /categories/:id" should {
    "delete a category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

      client.deleteCategory(created.id).futureValue
      val categories = client.getCategories.futureValue
      categories.data must be(empty)
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createCategory(CreateCategory.Request(CategoryName.trusted("Groceries"), description = None)).futureValue

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
