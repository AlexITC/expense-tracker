package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.{BankStatement, BankStatementRow, Expense}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.{Instant, LocalDate}
import java.util.UUID

class ReceiptRepositorySpec extends RepositorySpec with RepositoryUtils {

  "createWithDocument" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createReceipt(user.id).futureValue
    }

    "store the receipt with the Ingested status" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.find(created.id, user.id).futureValue.value.status must be(DocumentStatus.Ingested)
    }
  }

  "updateStatus" should {
    "change the receipt status" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.updateStatus(created.id, user.id, DocumentStatus.Parsed).futureValue
      repositories.receipts.find(created.id, user.id).futureValue.value.status must be(DocumentStatus.Parsed)
    }

    "fail when the receipt belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createReceipt(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.updateStatus(created.id, userB.id, DocumentStatus.Parsed).futureValue
      }
      ex.getMessage must include("Receipt not found")
      repositories.receipts.find(created.id, userA.id).futureValue.value.status must be(DocumentStatus.Ingested)
    }

    "fail when the receipt does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.updateStatus(UUID.randomUUID(), user.id, DocumentStatus.Parsed).futureValue
      }
      ex.getMessage must include("Receipt not found")
    }
  }

  "findByDocumentId" should {
    "return the receipt for an existing document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      val found = repositories.receipts.findByDocumentId(created.documentId).futureValue
      found.value.id must be(created.id)
    }

    "return None when no receipt exists for the document" in withRepositories() { implicit repositories =>
      repositories.receipts.findByDocumentId(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "find" should {
    "return the receipt when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      val found = repositories.receipts.find(created.id, user.id).futureValue
      found.value.userId must be(user.id)
    }

    "return None when the receipt belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val created = createReceipt(userA.id).futureValue

      repositories.receipts.find(created.id, userB.id).futureValue must be(None)
    }
  }

  "all" should {
    "return only the requesting user's receipts" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      createReceipt(userA.id).futureValue
      createReceipt(userA.id).futureValue
      createReceipt(userB.id).futureValue

      val results = repositories.receipts.all(userA.id).futureValue
      results.length must be(2)
    }

    "return an empty list when the user has no receipts" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.receipts.all(user.id).futureValue must be(empty)
    }
  }

  "ignoreReceipt / unignoreReceipt" should {
    "set and clear ignoredAt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.ignoreReceipt(created.id, user.id).futureValue
      val ignored = repositories.receipts.find(created.id, user.id).futureValue
      ignored.value.ignoredAt must be(defined)

      repositories.receipts.unignoreReceipt(created.id, user.id).futureValue
      val unignored = repositories.receipts.find(created.id, user.id).futureValue
      unignored.value.ignoredAt must be(None)
    }
  }

  "linkToExpense / unlinkExpense" should {
    "set and clear the expense link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.linkToExpense(created.id, user.id, expense.id).futureValue
      val linked = repositories.receipts.find(created.id, user.id).futureValue
      linked.value.expenseId must be(Some(expense.id))

      repositories.receipts.unlinkExpense(created.id, user.id).futureValue
      val unlinked = repositories.receipts.find(created.id, user.id).futureValue
      unlinked.value.expenseId must be(None)
    }

    "fail when the expense is already linked to another receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val first = createReceipt(user.id).futureValue
      val second = createReceipt(user.id).futureValue
      repositories.receipts.linkToExpense(first.id, user.id, expense.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.linkToExpense(second.id, user.id, expense.id).futureValue
      }
      ex.getCause.getMessage must be("That expense is already linked to another receipt")
    }

    "fail when the expense belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser().futureValue
      val userB = createUser().futureValue
      val expense = createExpense(userB.id).futureValue
      val receipt = createReceipt(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.linkToExpense(receipt.id, userA.id, expense.id).futureValue
      }
      ex.getCause.getMessage must be("Expense not found")
    }
  }

  "confirm" should {
    "create the expense and link both the receipt and the row" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val now = Instant.now()
      val stmt = BankStatement.Create(UUID.randomUUID(), doc.id, user.id, now, now)
      val row = BankStatementRow.Create(
        id = UUID.randomUUID(),
        bankStatementId = stmt.id,
        index = 0,
        date = LocalDate.of(2024, 1, 15),
        amount = BigDecimal("-50.00"),
        description = "Shop",
        manuallyEdited = false,
        createdAt = now,
        updatedAt = now,
        txnType = "purchase"
      )
      createStatement(stmt, sampleStatementMetadata, List(row)).futureValue
      val expense = Expense.Create(
        id = UUID.randomUUID(),
        userId = user.id,
        amount = BigDecimal("50.00"),
        date = LocalDate.of(2024, 1, 15),
        time = None,
        merchantId = None,
        notes = None,
        createdAt = now,
        updatedAt = now
      )

      val expenseId = repositories.receipts.confirm(receipt.id, user.id, expense, Nil, Some(row.id)).futureValue

      expenseId must be(expense.id)
      repositories.receipts.find(receipt.id, user.id).futureValue.value.expenseId must be(Some(expense.id))
      repositories.bankStatements.findRow(row.id, stmt.id).futureValue.value.expenseId must be(Some(expense.id))
    }
  }

  "linkToRefund / unlinkFromRefund" should {
    "set and clear the refund link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val refund = createRefund(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.linkToRefund(created.id, user.id, refund.id).futureValue
      val linked = repositories.receipts.find(created.id, user.id).futureValue
      linked.value.refundId must be(Some(refund.id))

      repositories.receipts.unlinkFromRefund(created.id, user.id).futureValue
      val unlinked = repositories.receipts.find(created.id, user.id).futureValue
      unlinked.value.refundId must be(None)
    }
  }

  "setMerchantId" should {
    "associate a merchant with the receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.setMerchantId(created.id, merchant.id).futureValue
      val found = repositories.receipts.find(created.id, user.id).futureValue
      found.value.merchantId must be(Some(merchant.id))
    }
  }
}
