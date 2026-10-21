package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.repositories.daos.{
  BackgroundJobDAO,
  BankStatementRowDAO,
  DocumentDAO,
  ExpenseCategorySplitDAO,
  ExpenseDAO,
  ReceiptDAO
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

  def findByDocumentId(documentId: UUID): Future[Option[Receipt]] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.findByDocumentId(documentId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Receipt]] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.find(id, userId)
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
      ReceiptDAO.updateParsed(id, amount, date, time, merchantName, taxId, parserVersion, clock.instant())
    }
  }

  def updateStatus(id: UUID, userId: UUID, status: DocumentStatus): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.updateStatus(id, userId, status, clock.instant())
    }
  }

  def confirm(
      id: UUID,
      userId: UUID,
      expense: Expense.Create,
      splits: List[ExpenseCategorySplit.Create],
      linkedRowId: Option[UUID] = None
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      ExpenseDAO.create(expense)
      ExpenseCategorySplitDAO.createAll(splits)
      ReceiptDAO.setExpenseId(id, userId, expense.id, clock.instant())
      linkedRowId.foreach { rowId =>
        BankStatementRowDAO.setExpenseIdForUser(rowId, userId, expense.id, clock.instant())
      }
      expense.id
    }
  }

  def linkToExpense(id: UUID, userId: UUID, expenseId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.setExpenseId(id, userId, expenseId, clock.instant())
    }
  }

  def unlinkExpense(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearExpenseId(id, userId, clock.instant())
    }
  }

  def linkToRefund(id: UUID, userId: UUID, refundId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.setRefundId(id, userId, refundId, clock.instant())
    }
  }

  def unlinkFromRefund(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearRefundId(id, userId, clock.instant())
    }
  }

  def ignoreReceipt(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      val now = clock.instant()
      ReceiptDAO.setIgnoredAt(id, userId, now, now)
    }
  }

  def unignoreReceipt(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.clearIgnoredAt(id, userId, clock.instant())
    }
  }

  def setMerchantId(id: UUID, merchantId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ReceiptDAO.setMerchantId(id, merchantId, clock.instant())
    }
  }
}
