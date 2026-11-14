package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.merchants.{CreateImportRule, CreateMerchant, ImportRuleAction, UpdateImportRule}
import net.wiringbits.api.utils.ImportRuleMatcher
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
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "AMZN",
              amount = None
            )
        )
        .futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "amazon prime",
              amount = None
            )
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
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "AMZN",
              amount = None
            )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "ab",
                amount = None
              )
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
              .Request(
                ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "AMZN",
              amount = None
            )
        )
        .futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .expectError
      error must be("A rule with that pattern, amount and merchant already exists")
    }

    "allow the same pattern for a different merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val m1 = client.createMerchant(CreateMerchant.Request("Amazon", None, None, None)).futureValue
      val m2 = client.createMerchant(CreateMerchant.Request("Walmart", None, None, None)).futureValue

      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(m1.id, categoryId = None, description = None), "AMZN", amount = None)
        )
        .futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetMerchant(m2.id, categoryId = None, description = None), "AMZN", amount = None)
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

      val response = client.createImportRule(CreateImportRule.Request(action, "CAFE", amount = None)).futureValue

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

      val error = client.createImportRule(CreateImportRule.Request(action, "CAFE", amount = None)).expectError
      error must be("Category not found")
    }
  }

  "POST/PUT /import-rules (description length)" should {
    "reject a description longer than the limit on create" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action = ImportRuleAction.SetCategory(
        category.id,
        description = Some("x" * (ImportRuleMatcher.MaxDescriptionLength + 1))
      )

      val error = client.createImportRule(CreateImportRule.Request(action, "CAFE", amount = None)).expectError
      error must be(s"Rule description must be ${ImportRuleMatcher.MaxDescriptionLength} characters or less")
      client.getImportRules.futureValue.data must be(empty)
    }

    "accept a description at the limit" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action =
        ImportRuleAction.SetCategory(category.id, description = Some("x" * ImportRuleMatcher.MaxDescriptionLength))

      val response = client.createImportRule(CreateImportRule.Request(action, "CAFE", amount = None)).futureValue
      client.getImportRules.futureValue.data.find(_.id == response.id).map(_.action) must be(Some(action))
    }

    "reject a description longer than the limit on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Coffee shop", None, None, None)).futureValue
      val plain = ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None)
      val created = client.createImportRule(CreateImportRule.Request(plain, "CAFE", amount = None)).futureValue

      val tooLong = ImportRuleAction.SetMerchant(
        merchant.id,
        categoryId = None,
        description = Some("x" * (ImportRuleMatcher.MaxDescriptionLength + 1))
      )
      val error =
        client.updateImportRule(created.id, UpdateImportRule.Request(tooLong, "CAFE", amount = None)).expectError
      error must be(s"Rule description must be ${ImportRuleMatcher.MaxDescriptionLength} characters or less")
      client.getImportRules.futureValue.data.find(_.id == created.id).map(_.action) must be(Some(plain))
    }
    "reject a blank description on create" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val action = ImportRuleAction.SetCategory(category.id, description = Some("  "))

      val error = client.createImportRule(CreateImportRule.Request(action, "CAFE", amount = None)).expectError
      error must be("Rule description must not be blank")
    }

    "reject a blank description on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Dining")).futureValue
      val plain = ImportRuleAction.SetCategory(category.id, description = None)
      val created = client.createImportRule(CreateImportRule.Request(plain, "CAFE", amount = None)).futureValue

      val blank = ImportRuleAction.SetCategory(category.id, description = Some(""))
      val error =
        client.updateImportRule(created.id, UpdateImportRule.Request(blank, "CAFE", amount = None)).expectError
      error must be("Rule description must not be blank")
    }
  }

  "POST/PUT /import-rules (pattern length)" should {
    val tooLong = "x" * (ImportRuleMatcher.MaxPatternLength + 1)

    "reject a pattern longer than the limit on create" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, tooLong, amount = None)).expectError
      error must be(s"Rule pattern must be ${ImportRuleMatcher.MaxPatternLength} characters or less")
      client.getImportRules.futureValue.data must be(empty)
    }

    "reject a pattern longer than the limit on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = None)).futureValue

      val error = client
        .updateImportRule(created.id, UpdateImportRule.Request(ImportRuleAction.Ignore, tooLong, amount = None))
        .expectError
      error must be(s"Rule pattern must be ${ImportRuleMatcher.MaxPatternLength} characters or less")
      client.getImportRules.futureValue.data.find(_.id == created.id).map(_.pattern) must be(Some("FEE"))
    }
  }

  "POST /import-rules (ignore)" should {
    "create an ignore rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", amount = None)).futureValue
      response.id must not be null

      val rules = client.getImportRules.futureValue
      val rule = rules.data.find(_.pattern == "AMZN")
      rule must not be empty
      rule.get.action must be(ImportRuleAction.Ignore)
    }

    "fail when duplicate ignore rule with same pattern" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", amount = None)).futureValue

      val error =
        client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "AMZN", amount = None)).expectError
      error must be("An ignore rule with that pattern and amount already exists")
    }
  }

  "POST/PUT /import-rules (amount)" should {
    "round-trip the amount through create, get and update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val created = client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("-1.50"))))
        .futureValue
      client.getImportRules.futureValue.data.find(_.id == created.id).flatMap(_.amount) must be(
        Some(BigDecimal("-1.50"))
      )

      client
        .updateImportRule(created.id, UpdateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = None))
        .futureValue
      client.getImportRules.futureValue.data.find(_.id == created.id).flatMap(_.amount) must be(None)
    }

    "allow a rule with an amount next to the same pattern without one" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = None)).futureValue
      client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("-1"))))
        .futureValue

      client.getImportRules.futureValue.data.size must be(2)
    }

    "reject a zero amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("0"))))
        .expectError
      error must be("Rule amount must not be zero")
    }

    "reject an amount with more than 2 decimals" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("1.234"))))
        .expectError
      error must be("Rule amount must have at most 2 decimal places")
    }

    "reject an amount that does not fit the column" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client
        .createImportRule(
          CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("-10000000000")))
        )
        .expectError
      error must be("Rule amount must have at most 10 integer digits")
    }

    "reject an invalid amount on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client
        .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("-1"))))
        .futureValue

      val error = client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.Ignore, "FEE", amount = Some(BigDecimal("0")))
        )
        .expectError
      error must be("Rule amount must not be zero")
      client.getImportRules.futureValue.data.find(_.id == created.id).flatMap(_.amount) must be(Some(BigDecimal("-1")))
    }
  }

  "POST /import-rules (set_category)" should {
    "create a set_category rule listed with its category name" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue

      val response =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None), "CAFETERIA", amount = None)
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
          )
          .expectError
      error must be("Category not found")
    }

    "fail when a rule with the same pattern and category already exists" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
        )
        .futureValue

      val error =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "cafeteria", amount = None)
          )
          .expectError
      error must be("A rule with that pattern, amount and category already exists")
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "amazon prime",
              amount = None
            )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "AMZN",
              amount = None
            )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "ab",
                amount = None
              )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error =
        client2
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(
                ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(
                ImportRuleAction.SetMerchant(UUID.randomUUID(), categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
            .Request(
              ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
              "walmart",
              amount = None
            )
        )
        .futureValue
      val other =
        client
          .createImportRule(
            CreateImportRule
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "walmart",
                amount = None
              )
          )
          .expectError
      error must be("A rule with that pattern, amount and merchant already exists")
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(c1.id, description = None), "CAFETERIA", amount = None)
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(ImportRuleAction.SetCategory(c2.id, description = None), "CAFE CENTRAL", amount = None)
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA", amount = None)
        )
        .futureValue

      val rule = client.getImportRules.futureValue.data.find(_.id == created.id).get
      rule.action must be(ImportRuleAction.Ignore)
      rule.action.categoryIdOpt must be(None)
    }

    "fail when the category does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created =
        client
          .createImportRule(CreateImportRule.Request(ImportRuleAction.Ignore, "CAFETERIA", amount = None))
          .futureValue

      val error =
        client
          .updateImportRule(
            created.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetCategory(UUID.randomUUID(), description = None), "CAFETERIA", amount = None)
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
          )
          .futureValue

      client
        .updateImportRule(
          created.id,
          UpdateImportRule
            .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
        )
        .futureValue
    }

    "fail when updating to a pattern+category combo already used by another rule" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val category = client.createCategory(CreateCategory.Request("Restaurants")).futureValue
      client
        .createImportRule(
          CreateImportRule
            .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
        )
        .futureValue
      val other =
        client
          .createImportRule(
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFE CENTRAL", amount = None)
          )
          .futureValue

      val error =
        client
          .updateImportRule(
            other.id,
            UpdateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
          )
          .expectError
      error must be("A rule with that pattern, amount and category already exists")
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
            CreateImportRule
              .Request(ImportRuleAction.SetCategory(category.id, description = None), "CAFETERIA", amount = None)
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
              .Request(
                ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
                "AMZN",
                amount = None
              )
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
