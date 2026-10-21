package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.daos.{DocumentDAO, ReceiptDAO}
import net.wiringbits.repositories.models.{BankStatement, Receipt}
import net.wiringbits.common.models.Email
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

class BugFixSpec extends RepositorySpec with RepositoryUtils {

  implicit class FutureOps[T](val f: Future[T]) {
    def expectError: String =
      f.map(_ => "Success when failure expected").recover { case ex => ex.getMessage }.futureValue
  }

  // Bug 1: ReceiptDAO.setExpenseId TOCTOU — second link should be rejected at the DB layer
  "ReceiptDAO.setExpenseId" should {
    "fail when the receipt already has an expense linked" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val expense1 = createExpense(user.id).futureValue
      val expense2 = createExpense(user.id, amount = BigDecimal("30.00")).futureValue
      val now = Instant.now()
      val receiptId = UUID.randomUUID()
      repositories.database.withConnection { implicit conn =>
        ReceiptDAO.create(
          Receipt.Create(
            id = receiptId,
            userId = user.id,
            documentId = doc.id,
            manuallyEdited = false,
            status = DocumentStatus.Ingested,
            createdAt = now,
            updatedAt = now
          )
        )
      }

      // First link succeeds
      repositories.database.withConnection { implicit conn =>
        ReceiptDAO.setExpenseId(receiptId, user.id, expense1.id, now)
      }

      // Second link with a different expense must be rejected
      val error = Future {
        repositories.database.withConnection { implicit conn =>
          ReceiptDAO.setExpenseId(receiptId, user.id, expense2.id, now)
        }
      }.expectError

      error must include("already linked")
    }
  }

  // Bug 2a: DocumentDAO.updateStatus silently ignores a non-existent ID
  "DocumentDAO.updateStatus" should {
    "fail when the document does not exist" in withRepositories() { implicit repositories =>
      val error = Future {
        repositories.database.withConnection { implicit conn =>
          DocumentDAO.updateStatus(UUID.randomUUID(), DocumentStatus.Parsed, None, Instant.now())
        }
      }.expectError

      error must include("not found")
    }
  }

  // Bug 2b: ReceiptDAO.updateParsed silently ignores a non-existent ID
  "ReceiptDAO.updateParsed" should {
    "fail when the receipt does not exist" in withRepositories() { implicit repositories =>
      val error = Future {
        repositories.database.withConnection { implicit conn =>
          ReceiptDAO.updateParsed(
            UUID.randomUUID(),
            amount = Some(BigDecimal("10.00")),
            date = None,
            time = None,
            merchantName = None,
            taxId = None,
            parserVersion = Some("v1"),
            updatedAt = Instant.now()
          )
        }
      }.expectError

      error must include("not found")
    }
  }
}
