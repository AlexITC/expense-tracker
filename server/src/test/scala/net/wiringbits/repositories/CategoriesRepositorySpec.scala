package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class CategoriesRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createCategory(user.id).futureValue
    }

    "fail when the user does not exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        createCategory(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("category_user_id_fk")
    }

    "fail when the same name exists for the same user (case-insensitive)" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createCategory(user.id, name = "Groceries").futureValue

        val ex = intercept[RuntimeException] {
          createCategory(user.id, name = "groceries").futureValue
        }
        ex.getCause.getMessage must include("A category with that name already exists")
    }

    "allow the same name for different users" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createCategory(userA.id, name = "Groceries").futureValue
      createCategory(userB.id, name = "Groceries").futureValue
    }
  }

  "all" should {
    "return only the requesting user's categories" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createCategory(userA.id, name = "Groceries").futureValue
      createCategory(userA.id, name = "Transport").futureValue
      createCategory(userB.id, name = "Utilities").futureValue

      val results = repositories.categories.all(userA.id).futureValue
      results.map(_.name) must contain theSameElementsAs List("Groceries", "Transport")
    }

    "return an empty list when the user has no categories" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.categories.all(user.id).futureValue must be(empty)
    }
  }

  "find" should {
    "return the category when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries").futureValue
      val found = repositories.categories.find(created.id, user.id).futureValue
      found.value.name must be("Groceries")
    }

    "return None when the category belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createCategory(userA.id).futureValue
      repositories.categories.find(created.id, userB.id).futureValue must be(None)
    }
  }

  "findByName" should {
    "find a category case-insensitively" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createCategory(user.id, name = "Groceries").futureValue

      val found = repositories.categories.findByName(user.id, "GROCERIES").futureValue
      found.value.name must be("Groceries")
    }

    "return None when no category matches" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.categories.findByName(user.id, "NonExistent").futureValue must be(None)
    }
  }

  "update" should {
    "rename a category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries").futureValue
      repositories.categories.update(created.id, user.id, "Food").futureValue
      val updated = repositories.categories.find(created.id, user.id).futureValue
      updated.value.name must be("Food")
    }

    "fail when renaming to a name already used by the same user (case-insensitive)" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createCategory(user.id, name = "Food").futureValue
        val other = createCategory(user.id, name = "Groceries").futureValue

        val ex = intercept[RuntimeException] {
          repositories.categories.update(other.id, user.id, "food").futureValue
        }
        ex.getCause.getMessage must include("A category with that name already exists")
    }

    "fail when the category does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createCategory(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.categories.update(created.id, userB.id, "NewName").futureValue
      }
      ex.getMessage must include("Category not found")
    }
  }

  "delete" should {
    "remove the category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id).futureValue
      repositories.categories.delete(created.id, user.id).futureValue
      repositories.categories.find(created.id, user.id).futureValue must be(None)
    }

    "fail when the category does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createCategory(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.categories.delete(created.id, userB.id).futureValue
      }
      ex.getMessage must include("Category not found")
    }
  }
}
