package net.wiringbits.repositories

import net.wiringbits.api.models.expenses.MergeDetailsSource
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

  "merge" should {
    def createExpenseWithSplit(
        userId: UUID,
        categoryId: UUID,
        amount: BigDecimal,
        date: LocalDate,
        merchantId: Option[UUID] = None,
        notes: Option[String] = None
    )(implicit repositories: RepositoryComponents): UUID = {
      val expId = UUID.randomUUID()
      val now = Instant.now()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = expId,
            userId = userId,
            amount = amount,
            date = date,
            time = None,
            merchantId = merchantId,
            notes = notes,
            createdAt = now,
            updatedAt = now
          ),
          List(ExpenseCategorySplit.Create(UUID.randomUUID(), userId, expId, categoryId, amount))
        )
        .futureValue
      expId
    }

    case class MergeFixture(
        userId: UUID,
        merchantId: UUID,
        survivorId: UUID,
        loserId: UUID,
        receiptId: UUID,
        refundId: UUID,
        otherRefundId: UUID
    )

    def mergeFixture()(implicit repositories: RepositoryComponents): MergeFixture = {
      val user = createUser().futureValue
      val category = createCategory(user.id).futureValue
      val merchant = createMerchant(user.id).futureValue
      val survivorId = createExpenseWithSplit(
        user.id,
        category.id,
        BigDecimal("42.50"),
        LocalDate.of(2024, 1, 15),
        Some(merchant.id),
        Some("bank")
      )
      val loserId = createExpenseWithSplit(user.id, category.id, BigDecimal("42.00"), LocalDate.of(2024, 1, 16))
      linkToNewRow(user.id, survivorId)
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, user.id, loserId).futureValue
      val refund = createRefund(user.id, expenseId = Some(loserId)).futureValue
      val otherRefund = createRefund(user.id).futureValue
      MergeFixture(user.id, merchant.id, survivorId, loserId, receipt.id, refund.id, otherRefund.id)
    }

    "move the loser's receipt to the survivor" in withRepositories() { implicit repositories =>
      val f = mergeFixture()

      repositories.expenses
        .merge(
          survivorId = f.survivorId,
          loserId = f.loserId,
          userId = f.userId,
          keepDetailsFrom = MergeDetailsSource.Row
        )
        .futureValue

      repositories.receipts.find(f.receiptId, f.userId).futureValue.value.expenseId must be(Some(f.survivorId))
    }

    "move the loser's refunds to the survivor and leave other refunds untouched" in withRepositories() {
      implicit repositories =>
        val f = mergeFixture()

        repositories.expenses
          .merge(
            survivorId = f.survivorId,
            loserId = f.loserId,
            userId = f.userId,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue

        repositories.refunds.find(f.refundId, f.userId).futureValue.value.expenseId must be(Some(f.survivorId))
        repositories.refunds.find(f.otherRefundId, f.userId).futureValue.value.expenseId must be(None)
    }

    "delete the loser and its splits" in withRepositories() { implicit repositories =>
      val f = mergeFixture()

      repositories.expenses
        .merge(
          survivorId = f.survivorId,
          loserId = f.loserId,
          userId = f.userId,
          keepDetailsFrom = MergeDetailsSource.Row
        )
        .futureValue

      repositories.expenses.find(f.loserId, f.userId).futureValue must be(None)
      repositories.expenses.findSplitsByExpenseId(f.loserId, f.userId).futureValue must be(empty)
    }

    "leave the survivor's fields, splits and row link unchanged" in withRepositories() { implicit repositories =>
      val f = mergeFixture()
      val splitsBefore = repositories.expenses.findSplitsByExpenseId(f.survivorId, f.userId).futureValue

      repositories.expenses
        .merge(
          survivorId = f.survivorId,
          loserId = f.loserId,
          userId = f.userId,
          keepDetailsFrom = MergeDetailsSource.Row
        )
        .futureValue

      val survivor = repositories.expenses.find(f.survivorId, f.userId).futureValue.value
      survivor.amount must be(BigDecimal("42.50"))
      survivor.date must be(LocalDate.of(2024, 1, 15))
      survivor.merchantId must be(Some(f.merchantId))
      survivor.notes must be(Some("bank"))
      repositories.expenses.findSplitsByExpenseId(f.survivorId, f.userId).futureValue must be(splitsBefore)
      repositories.bankStatements.allRows(f.userId).futureValue.map(_.expenseId) must be(List(Some(f.survivorId)))
    }

    "reject merging an expense with itself" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = expense.id,
            loserId = expense.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue
      )
      ex.getCause.getMessage must include("Cannot merge an expense with itself")
    }

    "reject another user's expense" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val survivor = createExpense(user.id).futureValue
      linkToNewRow(user.id, survivor.id)
      val loser = createExpense(other.id).futureValue
      val receipt = createReceipt(other.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, other.id, loser.id).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue
      )
      ex.getCause.getMessage must include("Expense not found")
      repositories.expenses.find(loser.id, other.id).futureValue mustNot be(None)
    }

    "reject a survivor owned by another user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val other = createUser().futureValue
      val survivor = createExpense(other.id).futureValue
      linkToNewRow(other.id, survivor.id)
      val loser = createExpense(user.id).futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, user.id, loser.id).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue
      )
      ex.getCause.getMessage must include("Expense not found")
      repositories.expenses.find(loser.id, user.id).futureValue mustNot be(None)
      repositories.receipts.find(receipt.id, user.id).futureValue.value.expenseId must be(Some(loser.id))
    }

    "reject a survivor without a bank statement row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val survivor = createExpense(user.id).futureValue
      val loser = createExpense(user.id).futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, user.id, loser.id).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue
      )
      ex.getCause.getMessage must include("The surviving expense must be linked to a bank statement row")
    }

    "reject a loser with a bank statement row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val survivor = createExpense(user.id).futureValue
      val loser = createExpense(user.id).futureValue
      linkToNewRow(user.id, survivor.id)
      linkToNewRow(user.id, loser.id)
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(receipt.id, user.id, loser.id).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue
      )
      ex.getCause.getMessage must include("The merged expense must not be linked to a bank statement row")
    }

    "delete a loser without a receipt and move its refunds to the survivor" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val survivor = createExpense(user.id).futureValue
        val loser = createExpense(user.id).futureValue
        linkToNewRow(user.id, survivor.id)
        val refund = createRefund(user.id, expenseId = Some(loser.id)).futureValue

        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Row
          )
          .futureValue

        repositories.expenses.find(loser.id, user.id).futureValue must be(None)
        repositories.expenses.find(survivor.id, user.id).futureValue mustNot be(None)
        repositories.refunds.find(refund.id, user.id).futureValue.value.expenseId must be(Some(survivor.id))
    }

    "reject keeping the details of a loser without a receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val survivor = createExpense(user.id).futureValue
      val loser = createExpense(user.id).futureValue
      linkToNewRow(user.id, survivor.id)
      val refund = createRefund(user.id, expenseId = Some(loser.id)).futureValue

      val ex = intercept[RuntimeException](
        repositories.expenses
          .merge(
            survivorId = survivor.id,
            loserId = loser.id,
            userId = user.id,
            keepDetailsFrom = MergeDetailsSource.Receipt
          )
          .futureValue
      )
      ex.getCause.getMessage must include("Cannot keep the duplicate's details because it has no receipt")
      repositories.expenses.find(loser.id, user.id).futureValue mustNot be(None)
      repositories.refunds.find(refund.id, user.id).futureValue.value.expenseId must be(Some(loser.id))
    }

    "reject a survivor that already has a receipt and leave everything unchanged" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val survivor = createExpense(user.id).futureValue
        val loser = createExpense(user.id).futureValue
        linkToNewRow(user.id, survivor.id)
        val survivorReceipt = createReceipt(user.id).futureValue
        repositories.receipts.linkToExpense(survivorReceipt.id, user.id, survivor.id).futureValue
        val loserReceipt = createReceipt(user.id).futureValue
        repositories.receipts.linkToExpense(loserReceipt.id, user.id, loser.id).futureValue

        val ex =
          intercept[RuntimeException](
            repositories.expenses
              .merge(
                survivorId = survivor.id,
                loserId = loser.id,
                userId = user.id,
                keepDetailsFrom = MergeDetailsSource.Row
              )
              .futureValue
          )
        ex.getCause.getMessage must include("The surviving expense already has a receipt")

        repositories.expenses.find(loser.id, user.id).futureValue mustNot be(None)
        repositories.receipts.find(loserReceipt.id, user.id).futureValue.value.expenseId must be(Some(loser.id))
        repositories.receipts.find(survivorReceipt.id, user.id).futureValue.value.expenseId must be(Some(survivor.id))
    }
    case class ReceiptModeFixture(
        userId: UUID,
        survivorId: UUID,
        survivorMerchantId: UUID,
        survivorCategoryId: UUID,
        loserId: UUID,
        loserMerchantId: UUID,
        loserCategoryIds: List[UUID],
        receiptId: UUID,
        refundId: UUID
    )

    def receiptModeFixture(loserAmount: BigDecimal = BigDecimal("42.50"), loserSplitAmounts: List[BigDecimal] = Nil)(
        implicit repositories: RepositoryComponents
    ): ReceiptModeFixture = {
      val user = createUser().futureValue
      val survivorCategory = createCategory(user.id).futureValue
      val survivorMerchant = createMerchant(user.id).futureValue
      val loserMerchant = createMerchant(user.id, name = "Receipt Merchant").futureValue
      val survivorId = createExpenseWithSplit(
        userId = user.id,
        categoryId = survivorCategory.id,
        amount = BigDecimal("42.50"),
        date = LocalDate.of(2024, 1, 15),
        merchantId = Some(survivorMerchant.id),
        notes = Some("bank")
      )
      val splitAmounts =
        if (loserSplitAmounts.isEmpty) List(BigDecimal("30.00"), loserAmount - BigDecimal("30.00"))
        else loserSplitAmounts
      val loserCategoryIds =
        splitAmounts.indices.toList.map(i => createCategory(user.id, name = s"Receipt Category $i").futureValue.id)
      val loserId = UUID.randomUUID()
      val now = Instant.now()
      repositories.expenses
        .create(
          net.wiringbits.repositories.models.Expense.Create(
            id = loserId,
            userId = user.id,
            amount = loserAmount,
            date = LocalDate.of(2024, 1, 16),
            time = None,
            merchantId = Some(loserMerchant.id),
            notes = Some("receipt"),
            createdAt = now,
            updatedAt = now
          ),
          loserCategoryIds.zip(splitAmounts).map { case (categoryId, amount) =>
            ExpenseCategorySplit.Create(
              id = UUID.randomUUID(),
              userId = user.id,
              expenseId = loserId,
              categoryId = categoryId,
              amount = amount
            )
          }
        )
        .futureValue
      linkToNewRow(userId = user.id, expenseId = survivorId)
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(id = receipt.id, userId = user.id, expenseId = loserId).futureValue
      val refund = createRefund(user.id, expenseId = Some(loserId)).futureValue
      ReceiptModeFixture(
        userId = user.id,
        survivorId = survivorId,
        survivorMerchantId = survivorMerchant.id,
        survivorCategoryId = survivorCategory.id,
        loserId = loserId,
        loserMerchantId = loserMerchant.id,
        loserCategoryIds = loserCategoryIds,
        receiptId = receipt.id,
        refundId = refund.id
      )
    }

    def mergeKeepingReceipt(f: ReceiptModeFixture)(implicit repositories: RepositoryComponents): Unit =
      repositories.expenses
        .merge(
          survivorId = f.survivorId,
          loserId = f.loserId,
          userId = f.userId,
          keepDetailsFrom = MergeDetailsSource.Receipt
        )
        .futureValue

    def assertReceiptModeMergeRejected(f: ReceiptModeFixture, message: String)(implicit
        repositories: RepositoryComponents
    ): Unit = {
      val ex = intercept[RuntimeException](mergeKeepingReceipt(f))
      ex.getCause.getMessage must include(message)

      val survivor = repositories.expenses.find(id = f.survivorId, userId = f.userId).futureValue.value
      survivor.merchantId must be(Some(f.survivorMerchantId))
      survivor.notes must be(Some("bank"))
      repositories.expenses
        .findSplitsByExpenseId(expenseId = f.survivorId, userId = f.userId)
        .futureValue
        .map(s => s.categoryId -> s.amount) must be(List(f.survivorCategoryId -> BigDecimal("42.50")))
      repositories.expenses.find(id = f.loserId, userId = f.userId).futureValue mustNot be(None)
      repositories.receipts.find(id = f.receiptId, userId = f.userId).futureValue.value.expenseId must be(
        Some(f.loserId)
      )
    }

    "adopt the loser's merchant and notes when keeping the receipt's details" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture()

        mergeKeepingReceipt(f)

        val survivor = repositories.expenses.find(id = f.survivorId, userId = f.userId).futureValue.value
        survivor.merchantId must be(Some(f.loserMerchantId))
        survivor.notes must be(Some("receipt"))
    }

    "adopt the loser's splits when keeping the receipt's details" in withRepositories() { implicit repositories =>
      val f = receiptModeFixture()

      mergeKeepingReceipt(f)

      repositories.expenses
        .findSplitsByExpenseId(expenseId = f.survivorId, userId = f.userId)
        .futureValue
        .map(s => s.categoryId -> s.amount)
        .toSet must be(f.loserCategoryIds.zip(List(BigDecimal("30.00"), BigDecimal("12.50"))).toSet)
    }

    "keep the survivor's amount, date, time and row link when keeping the receipt's details" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture()

        mergeKeepingReceipt(f)

        val survivor = repositories.expenses.find(id = f.survivorId, userId = f.userId).futureValue.value
        survivor.amount must be(BigDecimal("42.50"))
        survivor.date must be(LocalDate.of(2024, 1, 15))
        survivor.time must be(None)
        repositories.bankStatements.allRows(f.userId).futureValue.map(_.expenseId) must be(List(Some(f.survivorId)))
    }

    "move the loser's receipt to the survivor when keeping the receipt's details" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture()

        mergeKeepingReceipt(f)

        repositories.receipts
          .find(id = f.receiptId, userId = f.userId)
          .futureValue
          .value
          .expenseId must be(Some(f.survivorId))
    }

    "move the loser's refunds to the survivor when keeping the receipt's details" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture()

        mergeKeepingReceipt(f)

        repositories.refunds.find(id = f.refundId, userId = f.userId).futureValue.value.expenseId must be(
          Some(f.survivorId)
        )
    }

    "reject keeping the receipt's details when amounts differ and leave everything unchanged" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture(loserAmount = BigDecimal("42.00"))

        assertReceiptModeMergeRejected(f, "the expense amounts differ")
    }

    "reject keeping the receipt's details when the loser's splits don't sum to the amount" in withRepositories() {
      implicit repositories =>
        val f = receiptModeFixture(loserSplitAmounts = List(BigDecimal("30.00"), BigDecimal("10.00")))

        assertReceiptModeMergeRejected(f, "its category splits do not sum to the amount")
    }
  }
}
