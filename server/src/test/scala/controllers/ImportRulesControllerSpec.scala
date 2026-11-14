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
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
        )
        .futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "amazon prime")
        )
        .futureValue

      val response = client.getImportRules.futureValue
      response.data.map(_.pattern) must contain theSameElementsAs List("AMZN", "amazon prime")
    }

    "not return another user's rules" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
        )
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "ab")
          )
          .expectError
      error must be("Rule pattern must be at least 3 characters")
    }

    "fail when merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None), "AMZN")
          )
          .expectError
      error must be("Merchant not found")
    }

    "fail when a rule with the same pattern and merchant already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
        )
        .futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .expectError
      error must be("A rule with that pattern and merchant already exists")
    }

    "allow the same pattern for a different merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m1 = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val m2 = client.createMerchant(CreateMerchant.Request("Walmart", None, None, None)).futureValue

      client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.SetMerchant(m1.id, categoryId = None, description = None), "AMZN")
        )
        .futureValue
      client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.SetMerchant(m2.id, categoryId = None, description = None), "AMZN")
        )
        .futureValue

      val rules = client.getImportRules.futureValue
      rules.data must have size 2
    }
  }

  "POST /import-rules (set_merchant with override category and description)" should {
    "round-trip the override category and description" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Coffee shop", None, None, None)).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action =
        ImportRuleAction.SetMerchant(merchant.id, categoryId = Some(category.id), description = Some("Coffee"))

      val response = client.createImportRule(CreateImportRule.Request(action, "CAFE")).futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == response.id)
      rule.map(_.action) must be(Some(action))
      rule.flatMap(_.categoryName) must be(Some("Dining"))
    }

    "fail when the override category belongs to another user" in withApiClient { implicit client =>
      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val foreignCategory = client2.createCategory(CreateCategory.Request("Dining")).futureValue

      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Coffee shop", None, None, None)).futureValue
      val action =
        ImportRuleAction.SetMerchant(merchant.id, categoryId = Some(foreignCategory.id), description = None)

      val error = client.createImportRule(CreateImportRule.Request(action, "CAFE")).expectError
      error must be("Category not found")
    }
  }

  "POST/PUT /import-rules (description length)" should {
    "reject a description longer than the limit on create" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action = ImportRuleAction.SetCategory(
        category.id,
        description = Some("x" * (ImportRuleAction.MaxDescriptionLength + 1))
      )

      val error = client.createImportRule(CreateImportRule.Request(action, "CAFE")).expectError
      error must be(s"Rule description must be ${ImportRuleAction.MaxDescriptionLength} characters or less")
      client.getImportRules.futureValue.data must be(empty)
    }

    "accept a description at the limit" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action =
        ImportRuleAction.SetCategory(category.id, description = Some("x" * ImportRuleAction.MaxDescriptionLength))

      val response = client.createImportRule(CreateImportRule.Request(action, "CAFE")).futureValue
      client.getImportRules.futureValue.data.find(_.id == response.id).map(_.action) must be(Some(action))
    }

    "reject a description longer than the limit on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Coffee shop", None, None, None)).futureValue
      val plain = ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None)
      val created = client.createImportRule(CreateImportRule.Request(plain, "CAFE")).futureValue

      val tooLong = ImportRuleAction.SetMerchant(
        merchant.id,
        categoryId = None,
        description = Some("x" * (ImportRuleAction.MaxDescriptionLength + 1))
      )
      val error = client.updateImportRule(created.id, UpdateImportRule.Request(tooLong, "CAFE")).expectError
      error must be(s"Rule description must be ${ImportRuleAction.MaxDescriptionLength} characters or less")
      client.getImportRules.futureValue.data.find(_.id == created.id).map(_.action) must be(Some(plain))
    }
  }

  "POST /import-rules (ignore)" should {
    "create an ignore rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN")).futureValue
      response.id must not be null

      val rules = client.getImportRules.futureValue
      val rule = rules.data.find(_.pattern == "AMZN")
      rule must not be empty
      rule.get.action must be(ImportRuleAction.Ignore)
    }

    "fail when duplicate ignore rule with same pattern" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN")).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN")).expectError
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
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
          )
          .futureValue

      val rules = client.getImportRules.futureValue
      val rule = rules.data.find(_.id == response.id)
      rule must not be empty
      rule.get.action must be(ImportRuleAction.SetCategory(category.id, description = None))
      rule.get.categoryName must be(Some("Restaurants"))
    }

    "fail when the category does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None), "CAFETERIA")
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
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
          )
          .expectError
      error must be("Category not found")
    }

    "fail when a rule with the same pattern and category already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
        )
        .futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "cafeteria")
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "amazon prime")
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
        )
        .futureValue
    }

    "fail when pattern is shorter than 3 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "ab")
          )
          .expectError
      error must be("Rule pattern must be at least 3 characters")
    }

    "fail when the rule does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error =
        client2
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None), "AMZN")
          )
          .expectError
      error must be("Import rule not found")
    }

    "fail when merchant does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None), "AMZN")
          )
          .expectError
      error must be("Merchant not found")
    }

    "fail when updating to a pattern+merchant combo already used by another rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "walmart")
        )
        .futureValue
      val other =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "walmart")
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
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(c1.id, description = None), "CAFETERIA")
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.SetCategory(c2.id, description = None), "CAFE CENTRAL")
        )
        .futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == created.id).get
      rule.pattern must be("CAFE CENTRAL")
      rule.action must be(ImportRuleAction.SetCategory(c2.id, description = None))
      rule.categoryName must be(Some("Coffee"))
    }

    "clear the category when switching a set_category rule to ignore" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA")
        )
        .futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == created.id).get
      rule.action must be(ImportRuleAction.Ignore)
      rule.action.categoryIdOpt must be(None)
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA")).futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule.Request(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None), "CAFETERIA")
          )
          .expectError
      error must be("Category not found")
    }

    "allow updating a set_category rule to its own values" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      val created =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
        )
        .futureValue
    }

    "fail when updating to a pattern+category combo already used by another rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
        )
        .futureValue
      val other =
        client
          .createImportRule(
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFE CENTRAL")
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
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
            CreateImportRule.Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA")
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
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None), "AMZN")
          )
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
