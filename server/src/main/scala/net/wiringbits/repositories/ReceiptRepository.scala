package net.wiringbits.repositories
import net.wiringbits.repositories.support.ConstraintViolations

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.daos.{
  BackgroundJobDAO,
  BankStatementRowDAO,
  DocumentDAO,
  ExpenseCategorySplitDAO,
  ExpenseDAO,
  ReceiptDAO,
  TelegramDAO
}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Expense, ExpenseCategorySplit, Receipt}
import play.api.db.Database

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class ReceiptRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext, clock: Clock) {

  def createWithDocument(
      document: Document.Create,
      receipt: Receipt.Create,
      job: BackgroundJobData.Create
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.create(document)
      ReceiptDAO.create(receipt)
      BackgroundJobDAO.create(job)
      receipt.id
    }
  }

  /** Creates the receipt together with its Telegram link, so the enqueued job can always find the link. */
  def createWithDocumentFromTelegram(
      document: Document.Create,
      receipt: Receipt.Create,
      job: BackgroundJobData.Create,
      telegramReceiptId: UUID,
      chatId: Long,
      messageId: Option[Long]
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.create(document)
      ReceiptDAO.create(receipt)
      TelegramDAO.createReceipt(id = telegramReceiptId, receiptId = receipt.id, chatId, messageId)
      BackgroundJobDAO.create(job)
      receipt.id
    }
  }

  def findByDocumentId(documentId: UUID): Future[Option[Receipt]] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.findByDocumentId(documentId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Receipt]] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.find(id = id, userId = userId)
    }
  }

  def all(userId: UUID): Future[List[Receipt]] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.all(userId)
    }
  }

  def updateParsed(
      id: UUID,
      amount: Option[BigDecimal],
      date: Option[java.time.LocalDate],
      time: Option[java.time.LocalTime],
      merchantName: Option[String],
      taxId: Option[String],
      parserVersion: Option[String]
  ): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.updateParsed(
        id,
        amount,
        date,
        time,
        merchantName = merchantName,
        taxId = taxId,
        parserVersion = parserVersion,
        clock.instant()
      )
    }
  }

  def updateStatus(id: UUID, userId: UUID, status: DocumentStatus): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.updateStatus(id = id, userId = userId, status, clock.instant())
    }
  }

  def confirm(
      id: UUID,
      userId: UUID,
      expense: Expense.Create,
      splits: List[ExpenseCategorySplit.Create],
      linkedRowId: Option[UUID] = None
  ): Future[UUID] = Future {
    // The expense is created here, so neither the receipt nor the row unique index on expense_id can be violated.
    database.withTransaction { implicit conn =>
      ExpenseDAO.create(expense)
      ExpenseCategorySplitDAO.createAll(splits)
      ReceiptDAO.setExpenseId(id = id, userId = userId, expenseId = expense.id, clock.instant())
      linkedRowId.foreach { rowId =>
        BankStatementRowDAO.setExpenseIdForUser(rowId = rowId, userId = userId, expenseId = expense.id, clock.instant())
      }
      expense.id
    }
  }

  def linkToExpense(id: UUID, userId: UUID, expenseId: UUID): Future[Unit] = Future {
    ConstraintViolations.translating(ConstraintViolations.ReceiptExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        if (ExpenseDAO.find(id = expenseId, userId = userId).isEmpty) throw new RuntimeException("Expense not found")
        ReceiptDAO.setExpenseId(id = id, userId = userId, expenseId = expenseId, clock.instant())
      }
    }
  }

  def unlinkExpense(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearExpenseId(id = id, userId = userId, clock.instant())
    }
  }

  def linkToRefund(id: UUID, userId: UUID, refundId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.setRefundId(id = id, userId = userId, refundId = refundId, clock.instant())
    }
  }

  def unlinkFromRefund(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearRefundId(id = id, userId = userId, clock.instant())
    }
  }

  def ignoreReceipt(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      val now = clock.instant()
      ReceiptDAO.setIgnoredAt(id = id, userId = userId, ignoredAt = now, updatedAt = now)
    }
  }

  def unignoreReceipt(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearIgnoredAt(id = id, userId = userId, clock.instant())
    }
  }

  def setMerchantId(id: UUID, merchantId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.setMerchantId(id = id, merchantId = merchantId, clock.instant())
    }
  }
}
