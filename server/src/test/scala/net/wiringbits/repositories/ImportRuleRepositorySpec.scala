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
  }

  "all" should {
    "return only the requesting user's rules" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
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
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
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
        .update(id, user.id, ImportRuleAction.SetMerchant, "new-pattern", Some(merchant.id))
        .futureValue

      val updated = repositories.importRules.find(id, user.id).futureValue
      updated.value.pattern must be("new-pattern")
      updated.value.action must be(ImportRuleAction.SetMerchant)
      updated.value.merchantId must be(Some(merchant.id))
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
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val id = createImportRule(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.importRules.delete(id, userB.id).futureValue
      }
      ex.getMessage must include("Import rule not found")
    }
  }
}
