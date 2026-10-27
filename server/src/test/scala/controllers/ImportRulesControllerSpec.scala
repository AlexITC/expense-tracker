package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, ImportRuleAction, UpdateImportRule}
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

class ImportRulesControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  "GET /import-rules" should {
    "return an empty list when the user has no rules" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getImportRules.futureValue
      response.data must be(empty)
    }

    "return the user's rules" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
        .futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "amazon prime", Some(merchant.id)))
        .futureValue

      val response = client.getImportRules.futureValue
      response.data.map(_.pattern) must contain theSameElementsAs List("AMZN", "amazon prime")
    }

    "not return another user's rules" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
        .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      val response = client2.getImportRules.futureValue
      response.data must be(empty)
    }
  }

  "POST /import-rules (set_merchant)" should {
    "create a set_merchant rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val response =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue
      response.id must not be null

      val rules = client.getImportRules.futureValue
      rules.data.map(_.pattern) must contain("AMZN")
    }

    "fail when pattern is shorter than 3 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val error =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "ab", Some(merchant.id)))
          .expectError
      error must be("Rule pattern must be at least 3 characters")
    }

    "fail when merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(UUID.randomUUID())))
          .expectError
      error must be("Merchant not found")
    }

    "fail when merchantId is missing for set_merchant action" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", None)).expectError
      error must be("set_merchant rules require a merchant")
    }

    "fail when a rule with the same pattern and merchant already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
        .futureValue

      val error =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .expectError
      error must be("A rule with that pattern and merchant already exists")
    }

    "allow the same pattern for a different merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m1 = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val m2 = client.createMerchant(CreateMerchant.Request("Walmart", None, None, None)).futureValue

      client.createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(m1.id))).futureValue
      client.createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(m2.id))).futureValue

      val rules = client.getImportRules.futureValue
      rules.data must have size 2
    }
  }

  "POST /import-rules (ignore)" should {
    "create an ignore rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", None)).futureValue
      response.id must not be null

      val rules = client.getImportRules.futureValue
      val rule = rules.data.find(_.pattern == "AMZN")
      rule must not be empty
      rule.get.action must be(ImportRuleAction.Ignore)
      rule.get.merchantId must be(None)
    }

    "fail when merchantId is provided for ignore action" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val error =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", Some(merchant.id)))
          .expectError
      error must be("Ignore rules must not have a merchant")
    }

    "fail when duplicate ignore rule with same pattern" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", None)).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", None)).expectError
      error must be("An ignore rule with that pattern already exists")
    }
  }

  "POST /import-rules (set_category)" should {
    "create a set_category rule listed with its category name" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue

      val response =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .futureValue

      val rules = client.getImportRules.futureValue
      val rule = rules.data.find(_.id == response.id)
      rule must not be empty
      rule.get.action must be(ImportRuleAction.SetCategory)
      rule.get.merchantId must be(None)
      rule.get.categoryId must be(Some(category.id))
      rule.get.categoryName must be(Some("Restaurants"))
    }

    "fail when categoryId is missing" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None)).expectError
      error must be("set_category rules require a category")
    }

    "fail when the category does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(UUID.randomUUID()))
          )
          .expectError
      error must be("Category not found")
    }

    "fail when the category belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error =
        client2
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .expectError
      error must be("Category not found")
    }

    "fail when merchantId is provided" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", Some(merchant.id), Some(category.id))
          )
          .expectError
      error must be("set_category rules must not have a merchant")
    }

    "fail when a rule with the same pattern and category already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id)))
        .futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "cafeteria", None, Some(category.id))
          )
          .expectError
      error must be("A rule with that pattern and category already exists")
    }
  }

  "PUT /import-rules/:id" should {
    "update a rule's pattern" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.SetMerchant, "amazon prime", Some(merchant.id))
        )
        .futureValue

      val rules = client.getImportRules.futureValue
      rules.data.map(_.pattern) must contain("amazon prime")
      rules.data.map(_.pattern) must not contain "AMZN"
    }

    "allow updating a rule to its own values" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      client
        .updateImportRule(created.id, UpdateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
        .futureValue
    }

    "fail when pattern is shorter than 3 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      val error =
        client
          .updateImportRule(created.id, UpdateImportRule.Request(ImportRuleAction.SetMerchant, "ab", Some(merchant.id)))
          .expectError
      error must be("Rule pattern must be at least 3 characters")
    }

    "fail when the rule does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error =
        client2
          .updateImportRule(
            created.id,
            UpdateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(UUID.randomUUID()))
          )
          .expectError
      error must be("Import rule not found")
    }

    "fail when merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(UUID.randomUUID()))
          )
          .expectError
      error must be("Merchant not found")
    }

    "fail when updating to a pattern+merchant combo already used by another rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "walmart", Some(merchant.id)))
        .futureValue
      val other =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule.Request(ImportRuleAction.SetMerchant, "walmart", Some(merchant.id))
          )
          .expectError
      error must be("A rule with that pattern and merchant already exists")
    }
  }

  "PUT /import-rules/:id (set_category)" should {
    "update a set_category rule's pattern and category" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val c1 = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val c2 = client.createCategory(CreateCategory.Request("Coffee")).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(c1.id)))
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFE CENTRAL", None, Some(c2.id))
        )
        .futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == created.id).get
      rule.pattern must be("CAFE CENTRAL")
      rule.categoryId must be(Some(c2.id))
      rule.categoryName must be(Some("Coffee"))
    }

    "clear the category when switching a set_category rule to ignore" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA", None, Some(category.id))
        )
        .futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == created.id).get
      rule.action must be(ImportRuleAction.Ignore)
      rule.categoryId must be(None)
    }

    "fail when categoryId is missing" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA", None)).futureValue

      val error =
        client
          .updateImportRule(created.id, UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None))
          .expectError
      error must be("set_category rules require a category")
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA", None)).futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(UUID.randomUUID()))
          )
          .expectError
      error must be("Category not found")
    }

    "fail when merchantId is provided" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", Some(merchant.id), Some(category.id))
          )
          .expectError
      error must be("set_category rules must not have a merchant")
    }

    "allow updating a set_category rule to its own values" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
        )
        .futureValue
    }

    "fail when updating to a pattern+category combo already used by another rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
        )
        .futureValue
      val other =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFE CENTRAL", None, Some(category.id))
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .expectError
      error must be("A rule with that pattern and category already exists")
    }
  }

  "DELETE /import-rules/:id" should {
    "delete a rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      client.deleteImportRule(created.id).futureValue

      val rules = client.getImportRules.futureValue
      rules.data must be(empty)
    }

    "delete a set_category rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory, "CAFETERIA", None, Some(category.id))
          )
          .futureValue

      client.deleteImportRule(created.id).futureValue

      client.getImportRules.futureValue.data must be(empty)
    }

    "fail when the rule does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.SetMerchant, "AMZN", Some(merchant.id)))
          .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteImportRule(created.id).expectError
      error must be("Import rule not found")
    }

    "fail when the rule does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.deleteImportRule(UUID.randomUUID()).expectError
      error must be("Import rule not found")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getImportRules.expectError
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
