package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class SavingsRepositorySpec extends RepositorySpec with RepositoryUtils {

  "createGoal" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createSavingsGoal(user.id).futureValue
    }

    "fail when the user does not exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        createSavingsGoal(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("savings_goal_user_id_fk")
    }

    "fail on duplicate name for the same user (case-insensitive)" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createSavingsGoal(user.id, name = "Vacation").futureValue

      val ex = intercept[RuntimeException] {
        createSavingsGoal(user.id, name = "vacation").futureValue
      }
      ex.getCause.getMessage must include("savings_goal_user_id_name_unique")
    }
  }

  "allGoals" should {
    "return only the requesting user's goals" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      createSavingsGoal(userA.id, name = "Goal A1").futureValue
      createSavingsGoal(userA.id, name = "Goal A2").futureValue
      createSavingsGoal(userB.id, name = "Goal B1").futureValue

      val results = repositories.savings.allGoals(userA.id).futureValue
      results.length must be(2)
      results.map(_._1.name) must contain theSameElementsAs List("Goal A1", "Goal A2")
    }

    "include the summed balance for each goal" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      createSavingsAllocation(user.id, goal.id, amount = BigDecimal("300")).futureValue
      createSavingsAllocation(user.id, goal.id, amount = BigDecimal("200")).futureValue

      val results = repositories.savings.allGoals(user.id).futureValue
      results.head._2 must be(BigDecimal("500"))
    }
  }

  "findGoal" should {
    "return the goal when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createSavingsGoal(user.id, name = "Emergency Fund").futureValue
      val found = repositories.savings.findGoal(created.id, user.id).futureValue
      found.value.name must be("Emergency Fund")
    }

    "return None when the goal belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createSavingsGoal(userA.id).futureValue
      repositories.savings.findGoal(created.id, userB.id).futureValue must be(None)
    }
  }

  "findGoalByName" should {
    "find a goal case-insensitively" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createSavingsGoal(user.id, name = "Vacation").futureValue

      val found = repositories.savings.findGoalByName(user.id, "VACATION").futureValue
      found.value.name must be("Vacation")
    }

    "return None when no goal matches" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.savings.findGoalByName(user.id, "NonExistent").futureValue must be(None)
    }
  }

  "updateGoal" should {
    "update name and targetAmount" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createSavingsGoal(user.id, name = "Old Name", targetAmount = Some(BigDecimal("1000"))).futureValue

      repositories.savings.updateGoal(created.id, user.id, "New Name", None).futureValue
      val updated = repositories.savings.findGoal(created.id, user.id).futureValue
      updated.value.name must be("New Name")
      updated.value.targetAmount must be(None)
    }

    "fail when the goal does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createSavingsGoal(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.savings.updateGoal(created.id, userB.id, "New Name", None).futureValue
      }
      ex.getMessage must include("Savings goal not found")
    }
  }

  "deleteGoal" should {
    "remove the goal and its allocations" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      createSavingsAllocation(user.id, goal.id).futureValue
      createSavingsAllocation(user.id, goal.id).futureValue

      repositories.savings.deleteGoal(goal.id, user.id).futureValue
      repositories.savings.allGoals(user.id).futureValue must be(empty)
      repositories.savings.allAllocations(goal.id, user.id).futureValue must be(empty)
    }

    "fail when the goal does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createSavingsGoal(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.savings.deleteGoal(created.id, userB.id).futureValue
      }
      ex.getMessage must include("Savings goal not found")
    }
  }

  "createAllocation" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      createSavingsAllocation(user.id, goal.id, amount = BigDecimal("500")).futureValue
    }

    "support negative amounts (withdrawals)" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      createSavingsAllocation(user.id, goal.id, amount = BigDecimal("500")).futureValue
      createSavingsAllocation(user.id, goal.id, amount = BigDecimal("-100")).futureValue

      val goals = repositories.savings.allGoals(user.id).futureValue
      goals.head._2 must be(BigDecimal("400"))
    }
  }

  "allAllocations" should {
    "return only the goal's allocations" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal1 = createSavingsGoal(user.id, name = "Goal 1").futureValue
      val goal2 = createSavingsGoal(user.id, name = "Goal 2").futureValue
      createSavingsAllocation(user.id, goal1.id).futureValue
      createSavingsAllocation(user.id, goal1.id).futureValue
      createSavingsAllocation(user.id, goal2.id).futureValue

      val results = repositories.savings.allAllocations(goal1.id, user.id).futureValue
      results.length must be(2)
    }
  }

  "findAllocation" should {
    "return the allocation" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      val created = createSavingsAllocation(user.id, goal.id, amount = BigDecimal("250")).futureValue
      val found = repositories.savings.findAllocation(created.id, goal.id, user.id).futureValue
      found.value.amount must be(BigDecimal("250"))
    }

    "return None when the allocation belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val goal = createSavingsGoal(userA.id).futureValue
      val created = createSavingsAllocation(userA.id, goal.id).futureValue
      repositories.savings.findAllocation(created.id, goal.id, userB.id).futureValue must be(None)
    }
  }

  "updateAllocation" should {
    "update amount and date" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      val created = createSavingsAllocation(user.id, goal.id, amount = BigDecimal("100")).futureValue
      val newDate = LocalDate.of(2025, 6, 1)

      repositories.savings
        .updateAllocation(created.id, goal.id, user.id, BigDecimal("200"), newDate, Some("bonus"), None)
        .futureValue
      val updated = repositories.savings.findAllocation(created.id, goal.id, user.id).futureValue
      updated.value.amount must be(BigDecimal("200"))
      updated.value.date must be(newDate)
      updated.value.note must be(Some("bonus"))
    }
  }

  "deleteAllocation" should {
    "remove the allocation" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val goal = createSavingsGoal(user.id).futureValue
      val created = createSavingsAllocation(user.id, goal.id).futureValue

      repositories.savings.deleteAllocation(created.id, goal.id, user.id).futureValue
      repositories.savings.findAllocation(created.id, goal.id, user.id).futureValue must be(None)
    }

    "fail when the allocation does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val goal = createSavingsGoal(userA.id).futureValue
      val created = createSavingsAllocation(userA.id, goal.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.savings.deleteAllocation(created.id, goal.id, userB.id).futureValue
      }
      ex.getMessage must include("Savings allocation not found")
    }
  }

  "medianAmountByMerchant" should {
    "return the median expense amount for a merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      createExpense(user.id, amount = BigDecimal("100"), merchantId = Some(merchant.id)).futureValue
      createExpense(user.id, amount = BigDecimal("200"), merchantId = Some(merchant.id)).futureValue
      createExpense(user.id, amount = BigDecimal("300"), merchantId = Some(merchant.id)).futureValue

      val result = repositories.savings.medianAmountByMerchant(user.id, merchant.id).futureValue
      result must be(Some(BigDecimal("200")))
    }

    "return None when the merchant has no expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      repositories.savings.medianAmountByMerchant(user.id, merchant.id).futureValue must be(None)
    }
  }
}
