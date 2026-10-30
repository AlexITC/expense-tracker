package net.wiringbits.repositories

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.models.{BankStatementRow, ExpenseCategorySplit}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Instant, LocalDate}
import java.util.UUID

class ExpensesRepositorySpec extends RepositorySpec with RepositoryUtils {

  private def linkToNewRow(userId: UUID, expenseId: UUID)(implicit repositories: RepositoryComponents): Unit = {
    val stmt = createBankStatement(userId).futureValue
    val now = Instant.now()
    val row = BankStatementRow.Create(
      id = UUID.randomUUID(),
      bankStatementId = stmt.id,
      index = 0,
      date = LocalDate.of(2024, 1, 15),
      amount = BigDecimal("50.00"),
      description = "Row",
      manuallyEdited = false,
      createdAt = now,
      updatedAt = now,
      txnType = "purchase"
    )
    repositories.bankStatements.createRows(List(row)).futureValue
    repositories.bankStatements.setRowExpenseIdForUser(row.id, userId, expenseId, now).futureValue
  }

  "create" should {
    "work with no splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createExpense(user.id).futureValue
    }

    "work with splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val expId = UUID.randomUUID()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expId,
            userId = user.id,
            amount = BigDecimal("50.00"),
            date = LocalDate.of(2024, 1, 15),
            time = None,
            merchantId = None,
            notes = None,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          ),
          List(ExpenseCategorySplit.Create(UUID.randomUUID(), user.id, expId, category.id, BigDecimal("20.00")))
        )
        .futureValue
    }

    "work with a merchant" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      createExpense(user.id, merchantId = Some(merchant.id)).futureValue
    }

    "fail when user does not exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        createExpense(UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("expense_user_id_fk")
    }
  }

  "all" should {
    "return expenses for the user ordered by date desc" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createExpense(user.id, date = LocalDate.of(2024, 1, 10)).futureValue
      createExpense(user.id, date = LocalDate.of(2024, 3, 5)).futureValue
      createExpense(user.id, date = LocalDate.of(2024, 2, 20)).futureValue

      val result = repositories.expenses.all(user.id, None, None, false).futureValue
      result.length must be(3)
      result.map(_.date) must be(List(LocalDate.of(2024, 3, 5), LocalDate.of(2024, 2, 20), LocalDate.of(2024, 1, 10)))
    }

    "not return another user's expenses" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createExpense(userA.id).futureValue
      createExpense(userB.id).futureValue

      repositories.expenses.all(userA.id, None, None, false).futureValue.length must be(1)
      repositories.expenses.all(userB.id, None, None, false).futureValue.length must be(1)
    }

    "return empty list when user has no expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.expenses.all(user.id, None, None, false).futureValue must be(empty)
    }

    "return only expenses within the inclusive from/to bounds" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      List(9, 10, 15, 20, 21).foreach(d => createExpense(user.id, date = LocalDate.of(2024, 1, d)).futureValue)

      val result = repositories.expenses
        .all(user.id, Some(LocalDate.of(2024, 1, 10)), Some(LocalDate.of(2024, 1, 20)), false)
        .futureValue
      result.map(_.date) must be(List(20, 15, 10).map(LocalDate.of(2024, 1, _)))
    }

    "apply only the from bound when to is missing" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      List(9, 10, 21).foreach(d => createExpense(user.id, date = LocalDate.of(2024, 1, d)).futureValue)

      val result = repositories.expenses.all(user.id, Some(LocalDate.of(2024, 1, 10)), None, false).futureValue
      result.map(_.date) must be(List(21, 10).map(LocalDate.of(2024, 1, _)))
    }

    "apply only the to bound when from is missing" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      List(9, 10, 21).foreach(d => createExpense(user.id, date = LocalDate.of(2024, 1, d)).futureValue)

      val result = repositories.expenses.all(user.id, None, Some(LocalDate.of(2024, 1, 10)), false).futureValue
      result.map(_.date) must be(List(10, 9).map(LocalDate.of(2024, 1, _)))
    }

    "exclude expenses linked to a bank statement row when unlinkedOnly is set" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val linked = createExpense(user.id).futureValue
        val unlinked = createExpense(user.id).futureValue
        linkToNewRow(user.id, linked.id)

        repositories.expenses.all(user.id, None, None, true).futureValue.map(_.id) must be(List(unlinked.id))
    }

    "keep expenses linked only to a receipt when unlinkedOnly is set" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, user.id, expense.id).futureValue

      repositories.expenses.all(user.id, None, None, true).futureValue.map(_.id) must be(List(expense.id))
    }

    "return linked and unlinked expenses when no filters are given" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val linked = createExpense(user.id).futureValue
      val unlinked = createExpense(user.id).futureValue
      linkToNewRow(user.id, linked.id)

      repositories.expenses.all(user.id, None, None, false).futureValue.map(_.id).toSet must be(
        Set(linked.id, unlinked.id)
      )
    }
  }

  "allSplits" should {
    "return splits for the user's expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val expId = UUID.randomUUID()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expId,
            userId = user.id,
            amount = BigDecimal("50.00"),
            date = LocalDate.of(2024, 1, 15),
            time = None,
            merchantId = None,
            notes = None,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          ),
          List(ExpenseCategorySplit.Create(UUID.randomUUID(), user.id, expId, category.id, BigDecimal("20.00")))
        )
        .futureValue

      val splits = repositories.expenses.allSplits(user.id).futureValue
      splits.length must be(1)
      splits.head.categoryId must be(category.id)
    }

    "not return another user's splits" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val catA = createCategory(userA.id).futureValue
      val expIdA = UUID.randomUUID()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expIdA,
            userId = userA.id,
            amount = BigDecimal("50.00"),
            date = LocalDate.of(2024, 1, 15),
            time = None,
            merchantId = None,
            notes = None,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          ),
          List(
            ExpenseCategorySplit.Create(
              id = UUID.randomUUID(),
              userId = userA.id,
              expenseId = expIdA,
              categoryId = catA.id,
              amount = BigDecimal("20.00")
            )
          )
        )
        .futureValue

      repositories.expenses.allSplits(userB.id).futureValue must be(empty)
    }
  }

  "find" should {
    "return the expense when it exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createExpense(user.id, amount = BigDecimal("99.99")).futureValue
      val found = repositories.expenses.find(created.id, user.id).futureValue
      found.value.amount must be(BigDecimal("99.99"))
    }

    "return None when expense belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createExpense(userA.id).futureValue
      repositories.expenses.find(created.id, userB.id).futureValue must be(None)
    }

    "return None when expense does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.expenses.find(UUID.randomUUID(), user.id).futureValue must be(None)
    }
  }

  "update" should {
    "update expense fields and replace splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val created = createExpense(user.id, amount = BigDecimal("50.00")).futureValue

      val newSplit = ExpenseCategorySplit.Create(
        id = UUID.randomUUID(),
        userId = user.id,
        expenseId = created.id,
        categoryId = category.id,
        amount = BigDecimal("30.00")
      )
      repositories.expenses
        .update(
          created.id,
          user.id,
          BigDecimal("100.00"),
          LocalDate.of(2024, 6, 1),
          None,
          None,
          Some("updated"),
          List(newSplit)
        )
        .futureValue

      val updated = repositories.expenses.find(created.id, user.id).futureValue.value
      updated.amount must be(BigDecimal("100.00"))
      updated.notes must be(Some("updated"))

      val splits = repositories.expenses.allSplits(user.id).futureValue
      splits.length must be(1)
      splits.head.amount must be(BigDecimal("30.00"))
    }

    "clear splits when updated with empty list" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val expId = UUID.randomUUID()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expId,
            userId = user.id,
            amount = BigDecimal("50.00"),
            date = LocalDate.of(2024, 1, 15),
            time = None,
            merchantId = None,
            notes = None,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          ),
          List(
            ExpenseCategorySplit.Create(
              id = UUID.randomUUID(),
              userId = user.id,
              expenseId = expId,
              categoryId = category.id,
              amount = BigDecimal("20.00")
            )
          )
        )
        .futureValue

      repositories.expenses
        .update(expId, user.id, BigDecimal("50.00"), LocalDate.of(2024, 1, 15), None, None, None, Nil)
        .futureValue
      repositories.expenses.allSplits(user.id).futureValue must be(empty)
    }

    "fail when expense does not belong to user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createExpense(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.expenses
          .update(created.id, userB.id, BigDecimal("50.00"), LocalDate.of(2024, 1, 15), None, None, None, Nil)
          .futureValue
      }
      ex.getCause.getMessage must include("Expense not found")
    }
  }

  "delete" should {
    "remove the expense and cascade to splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val expId = UUID.randomUUID()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expId,
            userId = user.id,
            amount = BigDecimal("50.00"),
            date = LocalDate.of(2024, 1, 15),
            time = None,
            merchantId = None,
            notes = None,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          ),
          List(
            ExpenseCategorySplit.Create(
              id = UUID.randomUUID(),
              userId = user.id,
              expenseId = expId,
              categoryId = category.id,
              amount = BigDecimal("20.00")
            )
          )
        )
        .futureValue

      repositories.expenses.delete(expId, user.id).futureValue
      repositories.expenses.find(expId, user.id).futureValue must be(None)
      repositories.expenses.allSplits(user.id).futureValue must be(empty)
    }

    "fail when expense does not belong to user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createExpense(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.expenses.delete(created.id, userB.id).futureValue
      }
      ex.getCause.getMessage must include("Expense not found")
    }
  }

  "category delete" should {
    "cascade to expense_category_split rows but leave the expense intact" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val category = createCategory(user.id).futureValue
        val expId = UUID.randomUUID()
        repositories.expenses
          .create(
            net.wiringbits.repositories.models.Expense.Create(
              id = expId,
              userId = user.id,
              amount = BigDecimal("50.00"),
              date = LocalDate.of(2024, 1, 15),
              time = None,
              merchantId = None,
              notes = None,
              createdAt = java.time.Instant.now(),
              updatedAt = java.time.Instant.now()
            ),
            List(
              ExpenseCategorySplit.Create(
                id = UUID.randomUUID(),
                userId = user.id,
                expenseId = expId,
                categoryId = category.id,
                amount = BigDecimal("20.00")
              )
            )
          )
          .futureValue

        repositories.categories.delete(category.id, user.id).futureValue

        repositories.expenses.find(expId, user.id).futureValue must not be None
        repositories.expenses.allSplits(user.id).futureValue must be(empty)
    }
  }

  "merchant delete" should {
    "set merchant_id to null on referencing expenses" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val created = createExpense(user.id, merchantId = Some(merchant.id)).futureValue

      repositories.merchants.delete(merchant.id, user.id).futureValue

      val updated = repositories.expenses.find(created.id, user.id).futureValue.value
      updated.merchantId must be(None)
    }
  }

  "split uniqueness" should {
    "reject duplicate category in splits" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val expId = UUID.randomUUID()
      val ex = intercept[RuntimeException] {
        repositories.expenses
          .create(
            net.wiringbits.repositories.models.Expense.Create(
              id = expId,
              userId = user.id,
              amount = BigDecimal("50.00"),
              date = LocalDate.of(2024, 1, 15),
              time = None,
              merchantId = None,
              notes = None,
              createdAt = java.time.Instant.now(),
              updatedAt = java.time.Instant.now()
            ),
            List(
              ExpenseCategorySplit.Create(UUID.randomUUID(), user.id, expId, category.id, BigDecimal("10.00")),
              ExpenseCategorySplit.Create(UUID.randomUUID(), user.id, expId, category.id, BigDecimal("10.00"))
            )
          )
          .futureValue
      }
      ex.getCause.getMessage must include("expense_category_split_expense_category_unique")
    }
  }
}
