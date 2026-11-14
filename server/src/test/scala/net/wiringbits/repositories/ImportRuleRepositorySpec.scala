package net.wiringbits.repositories

import net.wiringbits.api.models.merchants.{ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID
import scala.concurrent.Future

class ImportRuleRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work with Ignore action" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "groceries", action = ImportRuleActionKind.Ignore).futureValue
      id must not be null
    }

    "work with SetMerchant action" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "amazon",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue
      id must not be null
    }

    "round-trip the category through all and find" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Groceries").futureValue
      val id = createImportRule(
        user.id,
        pattern = "walmart",
        action = ImportRuleActionKind.SetCategory,
        categoryId = Some(category.id)
      ).futureValue

      val found = repositories.importRules.find(id, user.id).futureValue.value
      found.action.categoryIdOpt must be(Some(category.id))
      found.categoryName must be(Some("Groceries"))

      val listed = repositories.importRules.all(user.id).futureValue.find(_.id == id).value
      listed.action.categoryIdOpt must be(Some(category.id))
      listed.categoryName must be(Some("Groceries"))
    }

    "be deleted when its category is deleted" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "walmart",
        action = ImportRuleActionKind.SetCategory,
        categoryId = Some(category.id)
      ).futureValue

      repositories.categories.delete(category.id, user.id).futureValue
      repositories.importRules.find(id, user.id).futureValue must be(None)
    }

    "round-trip a set-merchant override category and description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val category = createCategory(user.id, name = "Dining").futureValue
      val id = createImportRule(
        user.id,
        pattern = "cafe",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id),
        overrideCategoryId = Some(category.id),
        description = Some("Coffee")
      ).futureValue

      val expected =
        ImportRuleAction.SetMerchant(merchant.id, categoryId = Some(category.id), description = Some("Coffee"))
      val found = repositories.importRules.find(id, user.id).futureValue.value
      found.action must be(expected)
      found.categoryName must be(Some("Dining"))

      val listed = repositories.importRules.all(user.id).futureValue.find(_.id == id).value
      listed.action must be(expected)
      listed.categoryName must be(Some("Dining"))
    }

    "round-trip a set-category description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "walmart",
        action = ImportRuleActionKind.SetCategory,
        categoryId = Some(category.id),
        description = Some("Weekly shop")
      ).futureValue

      repositories.importRules.find(id, user.id).futureValue.value.action must be(
        ImportRuleAction.SetCategory(category.id, description = Some("Weekly shop"))
      )
    }

    "reject another user's override category and create no rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val foreignCategory = createCategory(other.id).futureValue

      val result = createImportRule(
        user.id,
        pattern = "cafe",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id),
        overrideCategoryId = Some(foreignCategory.id)
      )
      result.failed.futureValue.getMessage must be("Category not found")
      repositories.importRules.all(user.id).futureValue must be(empty)
    }

    "keep a set-merchant rule and clear its override when the category is deleted" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val merchant = createMerchant(user.id).futureValue
        val category = createCategory(user.id).futureValue
        val id = createImportRule(
          user.id,
          pattern = "cafe",
          action = ImportRuleActionKind.SetMerchant,
          merchantId = Some(merchant.id),
          overrideCategoryId = Some(category.id),
          description = Some("Coffee")
        ).futureValue

        repositories.categories.delete(category.id, user.id).futureValue
        val found = repositories.importRules.find(id, user.id).futureValue.value
        found.action must be(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = Some("Coffee")))
        found.categoryName must be(None)
    }
  }

  "create with an amount" should {
    "persist the amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "groceries", amount = Some(BigDecimal("-12.50"))).futureValue

      repositories.importRules.find(id, user.id).futureValue.value.amount must be(Some(BigDecimal("-12.50")))
      repositories.importRules.all(user.id).futureValue.find(_.id == id).value.amount must be(
        Some(BigDecimal("-12.50"))
      )
    }

    "allow the same pattern with different amounts and without an amount" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val merchant = createMerchant(user.id).futureValue
        def setMerchant(amount: Option[BigDecimal]) =
          createImportRule(
            user.id,
            pattern = "amazon",
            amount = amount,
            action = ImportRuleActionKind.SetMerchant,
            merchantId = Some(merchant.id)
          ).futureValue

        setMerchant(None)
        setMerchant(Some(BigDecimal("-10.00")))
        setMerchant(Some(BigDecimal("-20.00")))

        repositories.importRules.all(user.id).futureValue.flatMap(_.amount).toSet must be(
          Set(BigDecimal("-10.00"), BigDecimal("-20.00"))
        )
    }

    "reject a duplicate pattern and amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      def setCategory(amount: Option[BigDecimal]) = createImportRule(
        user.id,
        pattern = "cafe",
        amount = amount,
        action = ImportRuleActionKind.SetCategory,
        categoryId = Some(category.id)
      )

      setCategory(Some(BigDecimal("-3.20"))).futureValue
      setCategory(Some(BigDecimal("-3.2"))).failed.futureValue.getMessage must be(
        "A rule with that pattern, amount and category already exists"
      )
      setCategory(None).futureValue
      setCategory(None).failed.futureValue.getMessage must be(
        "A rule with that pattern, amount and category already exists"
      )
    }

    "reject a duplicate ignore rule without an amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createImportRule(user.id, pattern = "fee").futureValue
      createImportRule(user.id, pattern = "fee", amount = Some(BigDecimal("-1"))).futureValue

      createImportRule(user.id, pattern = "fee").failed.futureValue.getMessage must be(
        "An ignore rule with that pattern and amount already exists"
      )
    }
  }

  "all" should {
    "return only the requesting user's rules" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createImportRule(userA.id, pattern = "rule1").futureValue
      createImportRule(userA.id, pattern = "rule2").futureValue
      createImportRule(userB.id, pattern = "rule3").futureValue

      val results = repositories.importRules.all(userA.id).futureValue
      results.length must be(2)
      results.map(_.pattern) must contain theSameElementsAs List("rule1", "rule2")
    }

    "return an empty list when the user has no rules" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.importRules.all(user.id).futureValue must be(empty)
    }
  }

  "find" should {
    "return the rule when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "my-pattern").futureValue
      val found = repositories.importRules.find(id, user.id).futureValue
      found.value.pattern must be("my-pattern")
    }

    "return None when the rule belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val id = createImportRule(userA.id).futureValue
      repositories.importRules.find(id, userB.id).futureValue must be(None)
    }
  }

  "createSetMerchantIfAbsent" should {
    "create an auto-created set_merchant rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue

      val rules = repositories.importRules.all(user.id).futureValue
      rules.size must be(1)
      rules.head.action must be(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None))
      rules.head.pattern must be("amazon")
      rules.head.autoCreated must be(true)
    }

    "leave a single rule when called again, including with a case-variant pattern" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val merchant = createMerchant(user.id).futureValue

        repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue
        repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue
        repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "AMAZON").futureValue

        repositories.importRules.all(user.id).futureValue.size must be(1)
    }

    "leave a single rule under concurrent calls" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue

      Future
        .sequence((1 to 8).map(_ => repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon")))
        .futureValue

      repositories.importRules.all(user.id).futureValue.size must be(1)
    }

    "fail for another user's merchant and create no rule" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val merchant = createMerchant(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.importRules.createSetMerchantIfAbsent(userB.id, merchant.id, "amazon").futureValue
      }
      ex.getCause.getMessage must be("Merchant not found")
      repositories.importRules.all(userB.id).futureValue must be(empty)
      repositories.importRules.all(userA.id).futureValue must be(empty)
    }

    "add the amount-less rule next to a rule with an amount, once" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      createImportRule(
        user.id,
        pattern = "amazon",
        amount = Some(BigDecimal("-9.99")),
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue
      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue

      repositories.importRules.all(user.id).futureValue.map(_.amount).toSet must be(
        Set(None, Some(BigDecimal("-9.99")))
      )
    }

    "leave an existing manual rule untouched" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "amazon",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue

      val rules = repositories.importRules.all(user.id).futureValue
      rules.map(_.id) must be(List(id))
      rules.head.autoCreated must be(false)
    }
  }

  "update" should {
    "update the rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "old-pattern", action = ImportRuleActionKind.Ignore).futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules
        .update(
          id,
          user.id,
          ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None),
          "new-pattern",
          amount = None
        )
        .futureValue

      val updated = repositories.importRules.find(id, user.id).futureValue
      updated.value.pattern must be("new-pattern")
      updated.value.action must be(ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None))
    }

    "set and clear the amount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "fee").futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.Ignore, "fee", Some(BigDecimal("-1.50")))
        .futureValue
      repositories.importRules.find(id, user.id).futureValue.value.amount must be(Some(BigDecimal("-1.50")))

      repositories.importRules.update(id, user.id, ImportRuleAction.Ignore, "fee", amount = None).futureValue
      repositories.importRules.find(id, user.id).futureValue.value.amount must be(None)
    }

    "set and clear the category by changing the action" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Groceries").futureValue
      val id = createImportRule(user.id, pattern = "walmart").futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.SetCategory(category.id, description = None), "walmart", amount = None)
        .futureValue
      val withCategory = repositories.importRules.find(id, user.id).futureValue.value
      withCategory.action must be(ImportRuleAction.SetCategory(category.id, description = None))
      withCategory.categoryName must be(Some("Groceries"))

      repositories.importRules.update(id, user.id, ImportRuleAction.Ignore, "walmart", amount = None).futureValue
      val cleared = repositories.importRules.find(id, user.id).futureValue.value
      cleared.action must be(ImportRuleAction.Ignore)
      cleared.categoryName must be(None)
    }

    "set and clear the override category and description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val category = createCategory(user.id, name = "Dining").futureValue
      val id = createImportRule(
        user.id,
        pattern = "cafe",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      val withExtras =
        ImportRuleAction.SetMerchant(merchant.id, categoryId = Some(category.id), description = Some("Coffee"))
      repositories.importRules.update(id, user.id, withExtras, "cafe", amount = None).futureValue
      val updated = repositories.importRules.find(id, user.id).futureValue.value
      updated.action must be(withExtras)
      updated.categoryName must be(Some("Dining"))

      val plain = ImportRuleAction.SetMerchant(merchant.id, categoryId = None, description = None)
      repositories.importRules.update(id, user.id, plain, "cafe", amount = None).futureValue
      val cleared = repositories.importRules.find(id, user.id).futureValue.value
      cleared.action must be(plain)
      cleared.categoryName must be(None)
    }

    "reject another user's override category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val foreignCategory = createCategory(other.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "cafe",
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      val result = repositories.importRules.update(
        id,
        user.id,
        ImportRuleAction.SetMerchant(merchant.id, categoryId = Some(foreignCategory.id), description = None),
        "cafe",
        amount = None
      )
      result.failed.futureValue.getMessage must be("Category not found")
      repositories.importRules.find(id, user.id).futureValue.value.action.overrideCategoryIdOpt must be(None)
    }
  }

  "delete" should {
    "remove the rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id).futureValue

      repositories.importRules.delete(id, user.id).futureValue
      repositories.importRules.find(id, user.id).futureValue must be(None)
    }

    "fail when the rule does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val id = createImportRule(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.importRules.delete(id, userB.id).futureValue
      }
      ex.getMessage must include("Import rule not found")
    }
  }
}
