package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class MerchantsRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createMerchant(user.id).futureValue
    }

    "work with notes" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createMerchant(user.id, notes = Some("A good merchant")).futureValue
    }

    "fail when the user does not exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        createMerchant(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("merchant_user_id_fk")
    }

    "fail when the same name exists for the same user (case-insensitive)" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createMerchant(user.id, name = "Amazon").futureValue

        val ex = intercept[RuntimeException] {
          createMerchant(user.id, name = "amazon").futureValue
        }
        ex.getCause.getMessage must include("merchant_user_id_name_unique")
    }

    "allow the same name for different users" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createMerchant(userA.id, name = "Amazon").futureValue
      createMerchant(userB.id, name = "Amazon").futureValue
    }
  }

  "all" should {
    "return only the requesting user's merchants" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createMerchant(userA.id, name = "Amazon").futureValue
      createMerchant(userA.id, name = "Walmart").futureValue
      createMerchant(userB.id, name = "Target").futureValue

      val results = repositories.merchants.all(userA.id).futureValue
      results.map(_.name) must contain theSameElementsAs List("Amazon", "Walmart")
    }

    "return an empty list when the user has no merchants" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.merchants.all(user.id).futureValue must be(empty)
    }
  }

  "find" should {
    "return the merchant when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createMerchant(user.id, name = "Amazon", notes = Some("Prime member")).futureValue
      val found = repositories.merchants.find(created.id, user.id).futureValue
      found.value.name must be("Amazon")
      found.value.notes must be(Some("Prime member"))
    }

    "return None when the merchant belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createMerchant(userA.id).futureValue
      repositories.merchants.find(created.id, userB.id).futureValue must be(None)
    }
  }

  "findByName" should {
    "find a merchant case-insensitively" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createMerchant(user.id, name = "Amazon").futureValue

      val found = repositories.merchants.findByName(user.id, "AMAZON").futureValue
      found.value.name must be("Amazon")
    }

    "return None when no merchant matches" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.merchants.findByName(user.id, "NonExistent").futureValue must be(None)
    }
  }

  "update" should {
    "rename a merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createMerchant(user.id, name = "Amazon").futureValue
      repositories.merchants.update(created.id, user.id, "Amazon US", None, None, None).futureValue
      val updated = repositories.merchants.find(created.id, user.id).futureValue
      updated.value.name must be("Amazon US")
    }

    "update notes" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createMerchant(user.id, name = "Amazon").futureValue
      repositories.merchants.update(created.id, user.id, "Amazon", Some("New note"), None, None).futureValue
      val updated = repositories.merchants.find(created.id, user.id).futureValue
      updated.value.notes must be(Some("New note"))
    }

    "clear notes to null" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createMerchant(user.id, name = "Amazon", notes = Some("Old note")).futureValue
      repositories.merchants.update(created.id, user.id, "Amazon", None, None, None).futureValue
      val updated = repositories.merchants.find(created.id, user.id).futureValue
      updated.value.notes must be(None)
    }

    "fail when renaming to a name already used by the same user (case-insensitive)" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createMerchant(user.id, name = "Walmart").futureValue
        val other = createMerchant(user.id, name = "Amazon").futureValue

        val ex = intercept[RuntimeException] {
          repositories.merchants.update(other.id, user.id, "walmart", None, None, None).futureValue
        }
        ex.getCause.getMessage must include("merchant_user_id_name_unique")
    }

    "fail when the merchant does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createMerchant(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.merchants.update(created.id, userB.id, "NewName", None, None, None).futureValue
      }
      ex.getMessage must include("Merchant not found")
    }
  }

  "delete" should {
    "remove the merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createMerchant(user.id).futureValue
      repositories.merchants.delete(created.id, user.id).futureValue
      repositories.merchants.find(created.id, user.id).futureValue must be(None)
    }

    "fail when the merchant does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createMerchant(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.merchants.delete(created.id, userB.id).futureValue
      }
      ex.getMessage must include("Merchant not found")
    }
  }
}
