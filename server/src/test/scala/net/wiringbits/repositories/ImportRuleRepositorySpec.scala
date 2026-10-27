package net.wiringbits.repositories

import net.wiringbits.api.models.merchants.ImportRuleAction
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
      val id = createImportRule(user.id, pattern = "groceries", action = ImportRuleAction.Ignore).futureValue
      id must not be null
    }

    "work with SetMerchant action" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val id = createImportRule(
        user.id,
        pattern = "amazon",
        action = ImportRuleAction.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue
      id must not be null
    }

    "round-trip the category through all and find" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Groceries").futureValue
      val id = createImportRule(user.id, pattern = "walmart", categoryId = Some(category.id)).futureValue

      val found = repositories.importRules.find(id, user.id).futureValue.value
      found.categoryId must be(Some(category.id))
      found.categoryName must be(Some("Groceries"))

      val listed = repositories.importRules.all(user.id).futureValue.find(_.id == id).value
      listed.categoryId must be(Some(category.id))
      listed.categoryName must be(Some("Groceries"))
    }

    "be deleted when its category is deleted" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val id = createImportRule(user.id, pattern = "walmart", categoryId = Some(category.id)).futureValue

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

  "existsByUserPatternIgnore" should {
    "return true when an ignore rule with the same pattern exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createImportRule(user.id, pattern = "groceries", action = ImportRuleAction.Ignore).futureValue

      repositories.importRules.existsByUserPatternIgnore(user.id, "groceries").futureValue must be(true)
    }

    "return false when no matching ignore rule exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.importRules.existsByUserPatternIgnore(user.id, "groceries").futureValue must be(false)
    }

    "return false when excluded by id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "groceries", action = ImportRuleAction.Ignore).futureValue

      repositories.importRules
        .existsByUserPatternIgnore(user.id, "groceries", excludeId = Some(id))
        .futureValue must be(false)
    }
  }

  "existsByUserPatternMerchant" should {
    "return true when a matching rule exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      createImportRule(
        user.id,
        pattern = "amazon",
        action = ImportRuleAction.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue

      repositories.importRules.existsByUserPatternMerchant(user.id, "amazon", merchant.id).futureValue must be(true)
    }

    "return false when no matching rule exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      repositories.importRules.existsByUserPatternMerchant(user.id, "amazon", merchant.id).futureValue must be(false)
    }
  }

  "update" should {
    "update the rule" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val id = createImportRule(user.id, pattern = "old-pattern", action = ImportRuleAction.Ignore).futureValue
      val merchant = createMerchant(user.id).futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.SetMerchant, "new-pattern", Some(merchant.id), None)
        .futureValue

      val updated = repositories.importRules.find(id, user.id).futureValue
      updated.value.pattern must be("new-pattern")
      updated.value.action must be(ImportRuleAction.SetMerchant)
      updated.value.merchantId must be(Some(merchant.id))
    }

    "set and clear the category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id, name = "Groceries").futureValue
      val id = createImportRule(user.id, pattern = "walmart").futureValue

      repositories.importRules
        .update(id, user.id, ImportRuleAction.Ignore, "walmart", None, Some(category.id))
        .futureValue
      val withCategory = repositories.importRules.find(id, user.id).futureValue.value
      withCategory.categoryId must be(Some(category.id))
      withCategory.categoryName must be(Some("Groceries"))

      repositories.importRules.update(id, user.id, ImportRuleAction.Ignore, "walmart", None, None).futureValue
      val cleared = repositories.importRules.find(id, user.id).futureValue.value
      cleared.categoryId must be(None)
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
