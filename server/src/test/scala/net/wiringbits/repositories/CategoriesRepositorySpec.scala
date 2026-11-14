package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID
import scala.concurrent.Future

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

    "store the description so find and all read it back" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries", description = Some("Supermarket runs")).futureValue

      repositories.categories.find(created.id, user.id).futureValue.value.description must be(Some("Supermarket runs"))
      repositories.categories.all(user.id).futureValue.map(_.description) must be(List(Some("Supermarket runs")))
    }

    "read back None when created without a description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries").futureValue

      repositories.categories.find(created.id, user.id).futureValue.value.description must be(None)
      repositories.categories.all(user.id).futureValue.map(_.description) must be(List(None))
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

  "getOrCreate" should {
    "create the category when absent" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = repositories.categories.getOrCreate(user.id, "Groceries", description = None).futureValue

      category.name must be("Groceries")
      repositories.categories.all(user.id).futureValue.map(_.id) must be(List(category.id))
    }

    "return the existing id and stored name for a case-variant name" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createCategory(user.id, name = "Groceries").futureValue

      val category = repositories.categories.getOrCreate(user.id, "GROCERIES", description = None).futureValue
      category.id must be(existing.id)
      category.name must be("Groceries")
      repositories.categories.all(user.id).futureValue.size must be(1)
    }

    "return the same id on sequential calls" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val first = repositories.categories.getOrCreate(user.id, "Groceries", description = None).futureValue
      val second = repositories.categories.getOrCreate(user.id, "Groceries", description = None).futureValue

      second.id must be(first.id)
      repositories.categories.all(user.id).futureValue.size must be(1)
    }

    "converge on one row under concurrent calls" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val results =
        Future
          .sequence((1 to 8).map(_ => repositories.categories.getOrCreate(user.id, "Groceries", description = None)))
          .futureValue

      results.map(_.id).distinct.size must be(1)
      repositories.categories.all(user.id).futureValue.size must be(1)
    }

    "create separate rows for different users" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val a = repositories.categories.getOrCreate(userA.id, "Groceries", description = None).futureValue
      val b = repositories.categories.getOrCreate(userB.id, "Groceries", description = None).futureValue

      a.id must not be b.id
      repositories.categories.all(userA.id).futureValue.map(_.id) must be(List(a.id))
      repositories.categories.all(userB.id).futureValue.map(_.id) must be(List(b.id))
    }

    "leave the stored description unchanged when the name already exists" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val existing = createCategory(user.id, name = "Groceries", description = Some("Original")).futureValue

        val category =
          repositories.categories.getOrCreate(user.id, "Groceries", description = Some("Replacement")).futureValue
        category.id must be(existing.id)
        category.description must be(Some("Original"))
        repositories.categories.find(existing.id, user.id).futureValue.value.description must be(Some("Original"))
    }
  }

  "update" should {
    "rename a category" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries").futureValue
      repositories.categories.update(created.id, user.id, "Food", description = None).futureValue
      val updated = repositories.categories.find(created.id, user.id).futureValue
      updated.value.name must be("Food")
    }

    "set, change and clear the description" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createCategory(user.id, name = "Groceries").futureValue
      def stored = repositories.categories.find(created.id, user.id).futureValue.value

      repositories.categories.update(created.id, user.id, "Groceries", description = Some("Weekly shop")).futureValue
      stored.description must be(Some("Weekly shop"))

      repositories.categories.update(created.id, user.id, "Groceries", description = Some("Any food")).futureValue
      stored.description must be(Some("Any food"))

      repositories.categories.update(created.id, user.id, "Groceries", description = None).futureValue
      stored.description must be(None)
      stored.name must be("Groceries")
    }

    "fail when renaming to a name already used by the same user (case-insensitive)" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        createCategory(user.id, name = "Food").futureValue
        val other = createCategory(user.id, name = "Groceries").futureValue

        val ex = intercept[RuntimeException] {
          repositories.categories.update(other.id, user.id, "food", description = None).futureValue
        }
        ex.getCause.getMessage must include("A category with that name already exists")
    }

    "fail when the category does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createCategory(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.categories.update(created.id, userB.id, "NewName", description = Some("Other")).futureValue
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
