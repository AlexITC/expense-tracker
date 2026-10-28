package net.wiringbits.repositories

import net.wiringbits.api.models.merchants.{ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

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
    "create a set_merchant rule when none exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue

      val rules = repositories.importRules.all(user.id).futureValue
      rules.find(_.pattern == "amazon").value.action must be(ImportRuleAction.SetMerchant(merchant.id))
    }

    "be a no-op when a matching rule already exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue
      repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue

      repositories.importRules.all(user.id).futureValue.count(_.pattern == "amazon") must be(1)
    }

    "fail when the merchant belongs to another user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val merchant = createMerchant(other.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.importRules.createSetMerchantIfAbsent(user.id, merchant.id, "amazon").futureValue
      }
      ex.getMessage must include("Merchant not found")
    }
  }

  "update" should {
    "update the rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "old-pattern", action = ImportRuleActionKind.Ignore).futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.SetMerchant(merchant.id), "new-pattern")
        .futureValue

      val updated = repositories.importRules.find(id, user.id).futureValue
      updated.value.pattern must be("new-pattern")
      updated.value.action must be(ImportRuleAction.SetMerchant(merchant.id))
    }

    "set and clear the category by changing the action" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Groceries").futureValue
      val id = createImportRule(user.id, pattern = "walmart").futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.SetCategory(category.id), "walmart")
        .futureValue
      val withCategory = repositories.importRules.find(id, user.id).futureValue.value
      withCategory.action must be(ImportRuleAction.SetCategory(category.id))
      withCategory.categoryName must be(Some("Groceries"))

      repositories.importRules.update(id, user.id, ImportRuleAction.Ignore, "walmart").futureValue
      val cleared = repositories.importRules.find(id, user.id).futureValue.value
      cleared.action must be(ImportRuleAction.Ignore)
      cleared.categoryName must be(None)
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
