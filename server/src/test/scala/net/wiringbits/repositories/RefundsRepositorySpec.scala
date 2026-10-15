package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.LocalDate
import java.util.UUID

class RefundsRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createRefund(user.id).futureValue
    }

    "fail when the user does not exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        createRefund(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("refund_user_id_fk")
    }
  }

  "find" should {
    "return the refund when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createRefund(user.id, amount = BigDecimal("75.00")).futureValue
      val found = repositories.refunds.find(created.id, user.id).futureValue
      found.value.amount must be(BigDecimal("75.00"))
    }

    "return None when the refund belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createRefund(userA.id).futureValue
      repositories.refunds.find(created.id, userB.id).futureValue must be(None)
    }

    "return None when the refund does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.refunds.find(UUID.randomUUID(), user.id).futureValue must be(None)
    }
  }

  "all" should {
    "return only the requesting user's refunds" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      createRefund(userA.id).futureValue
      createRefund(userA.id).futureValue
      createRefund(userB.id).futureValue

      val results = repositories.refunds.all(userA.id).futureValue
      results.length must be(2)
    }

    "return an empty list when the user has no refunds" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.refunds.all(user.id).futureValue must be(empty)
    }
  }

  "update" should {
    "update the refund fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createRefund(user.id, amount = BigDecimal("50.00")).futureValue
      val newDate = LocalDate.of(2025, 6, 1)

      repositories.refunds
        .update(created.id, user.id, BigDecimal("99.00"), newDate, None, None, Some("updated"))
        .futureValue
      val updated = repositories.refunds.find(created.id, user.id).futureValue
      updated.value.amount must be(BigDecimal("99.00"))
      updated.value.date must be(newDate)
      updated.value.notes must be(Some("updated"))
    }
  }

  "delete" should {
    "remove the refund" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createRefund(user.id).futureValue
      repositories.refunds.delete(created.id, user.id).futureValue
      repositories.refunds.find(created.id, user.id).futureValue must be(None)
    }

    "fail when the refund does not belong to the user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createRefund(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.refunds.delete(created.id, userB.id).futureValue
      }
      ex.getMessage must include("Refund not found")
    }
  }

  "linkToExpense / unlinkFromExpense" should {
    "set and clear the expense link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val refund = createRefund(user.id).futureValue

      repositories.refunds.linkToExpense(refund.id, user.id, expense.id).futureValue
      val linked = repositories.refunds.find(refund.id, user.id).futureValue
      linked.value.expenseId must be(Some(expense.id))

      repositories.refunds.unlinkFromExpense(refund.id, user.id).futureValue
      val unlinked = repositories.refunds.find(refund.id, user.id).futureValue
      unlinked.value.expenseId must be(None)
    }
  }
}
