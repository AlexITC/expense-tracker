package net.wiringbits.actions.telegram

import net.wiringbits.api.models.merchants.{ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.config.MerchantsConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.services.TelegramMessageParser.ParsedMessage
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Clock, LocalDate}
import scala.concurrent.Future

class TelegramExpenseActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction(maxPerUser: Int = 400)(using repositories: RepositoryComponents): TelegramExpenseAction =
    new TelegramExpenseAction(
      repositories.merchants,
      repositories.categories,
      repositories.expenses,
      repositories.importRules,
      MerchantsConfig(maxPerUser)
    )(using executionContext, Clock.systemUTC())

  "apply" should {

    // Branch 1: merchant found by name, no category → use merchant's existing categoryId
    "create expense using the merchant's existing category when none is given in the message" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val category = createCategory(user.id, name = "Groceries").futureValue
        val merchant = createMerchant(user.id, name = "Walmart", categoryId = Some(category.id)).futureValue

        val msg = ParsedMessage(BigDecimal("25.00"), "Walmart", None, None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Right[?, ?]]
        val expense = repositories.expenses.all(user.id, None, None, false).futureValue.headOption.value
        expense.amount must be(BigDecimal("25.00"))
        expense.merchantId must be(Some(merchant.id))
        val splits = repositories.expenses.allSplits(user.id).futureValue
        splits.map(_.categoryId) must contain(category.id)
    }

    // Branch 2: merchant found by name, category given and already exists → use it
    "use an existing category when merchant is found by name and category is given" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val category = createCategory(user.id, name = "Food").futureValue
        createMerchant(user.id, name = "Starbucks").futureValue

        val msg = ParsedMessage(BigDecimal("5.50"), "Starbucks", Some("Food"), None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Right[?, ?]]
        val splits = repositories.expenses.allSplits(user.id).futureValue
        splits.map(_.categoryId) must contain(category.id)
        repositories.categories.all(user.id).futureValue.length must be(1)
    }

    // Branch 3: merchant found by name, category given but absent → auto-create category
    "auto-create a category when merchant is found but the category name is unknown" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createMerchant(user.id, name = "Netflix").futureValue

        val msg = ParsedMessage(BigDecimal("12.99"), "Netflix", Some("Entertainment"), None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Right[?, ?]]
        val categories = repositories.categories.all(user.id).futureValue
        categories.map(_.name) must contain("Entertainment")
        val splits = repositories.expenses.allSplits(user.id).futureValue
        splits must not be empty
    }

    // Branch 4: merchant not found by name but matched by an import rule
    "create expense with a merchant resolved via an import rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Shopping").futureValue
      val merchant = createMerchant(user.id, name = "Amazon", categoryId = Some(category.id)).futureValue
      createImportRule(
        user.id,
        pattern = "amzn",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      // "AMZN.COM" normalizes to "amzncom" which contains the pattern "amzn"
      val msg = ParsedMessage(BigDecimal("30.00"), "AMZN.COM", None, None)
      val result = newAction().apply(user.id, msg).futureValue

      result mustBe a[Right[?, ?]]
      val expense = repositories.expenses.all(user.id, None, None, false).futureValue.headOption.value
      expense.merchantId must be(Some(merchant.id))
      val splits = repositories.expenses.allSplits(user.id).futureValue
      splits.map(_.categoryId) must contain(category.id)
    }

    // Branch 5: merchant not found, no category → Left
    "return Left when merchant is not found and no category is provided" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue

        val msg = ParsedMessage(BigDecimal("10.00"), "Unknown Shop", None, None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Left[?, ?]]
        result.left.getOrElse("") must include("Unknown Shop")
    }

    // Branch 6: merchant not found, category given → create merchant and expense
    "create merchant and expense when merchant is not found but category is provided" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue

        val msg = ParsedMessage(BigDecimal("8.00"), "New Shop", Some("Misc"), None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Right[?, ?]]
        val merchants = repositories.merchants.all(user.id).futureValue
        merchants.map(_.name) must contain("New Shop")
        val categories = repositories.categories.all(user.id).futureValue
        categories.map(_.name) must contain("Misc")
        val expense = repositories.expenses.all(user.id, None, None, false).futureValue.headOption.value
        expense.amount must be(BigDecimal("8.00"))
        val splits = repositories.expenses.allSplits(user.id).futureValue
        splits must not be empty
    }

    "return Left when the configured merchant limit is reached and a new merchant would be created" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createMerchant(user.id, name = "Shop A").futureValue
        createMerchant(user.id, name = "Shop B").futureValue

        val msg = ParsedMessage(BigDecimal("8.00"), "New Shop", Some("Misc"), None)
        val result = newAction(maxPerUser = 2).apply(user.id, msg).futureValue

        result must be(Left("Maximum number of merchants (2) reached"))
        repositories.merchants.count(user.id).futureValue must be(2L)
    }

    // Ignore rule: return Left without creating any expense
    "return Left when an Ignore import rule matches, even when a category is provided" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createImportRule(user.id, pattern = "amzn", action = ImportRuleActionKind.Ignore).futureValue

        val msg = ParsedMessage(BigDecimal("10.00"), "AMZN.COM", Some("Shopping"), None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Left[?, ?]]
        repositories.expenses.all(user.id, None, None, false).futureValue mustBe empty
    }

    // Monthly expense cap
    "return Left when the monthly expense cap of 500 is reached" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val today = LocalDate.now()
      Future.traverse((1 to 500).toList)(_ => createExpense(user.id, date = today)).futureValue

      val msg = ParsedMessage(BigDecimal("5.00"), "Any Shop", None, None)
      val result = newAction().apply(user.id, msg).futureValue

      result mustBe a[Left[?, ?]]
      result.left.getOrElse("") must include("500")
      repositories.expenses.all(user.id, None, None, false).futureValue.length must be(500)
    }

    // Merchant with no categoryId and no category in message → Right, no splits
    "create expense without splits when merchant has no category and none is given" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val merchant = createMerchant(user.id, name = "Petrol", categoryId = None).futureValue

        val msg = ParsedMessage(BigDecimal("40.00"), "Petrol", None, None)
        val result = newAction().apply(user.id, msg).futureValue

        result mustBe a[Right[?, ?]]
        val expense = repositories.expenses.all(user.id, None, None, false).futureValue.headOption.value
        expense.merchantId must be(Some(merchant.id))
        repositories.expenses.allSplits(user.id).futureValue mustBe empty
    }
  }
}
