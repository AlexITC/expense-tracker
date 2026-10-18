package net.wiringbits.repositories

import net.wiringbits.api.models.bankstatements.{AiSuggestions, ExtractionInfo}
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{BackgroundJobDAO, BankStatementDAO, BankStatementRowDAO, DocumentDAO}
import net.wiringbits.repositories.models.{
  BackgroundJobData,
  BankStatement,
  BankStatementRow,
  BankStatementView,
  Document
}
import play.api.db.Database

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class BankStatementRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {

  /** Confirm a draft import: create the document + statement (with metadata) + rows in one transaction, and enqueue the
    * given job (the AI suggestions job; the rows are already extracted). Used by the AI draft-import flow.
    */
  def createConfirmedStatement(
      document: Document.Create,
      stmt: BankStatement.Create,
      metadata: BankStatement.Metadata,
      rows: List[BankStatementRow.Create],
      extractionInfo: Option[ExtractionInfo],
      job: BackgroundJobData.Create
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      DocumentDAO.create(document)
      BankStatementDAO.create(stmt, metadata, extractionInfo)
      BankStatementRowDAO.createAll(rows)
      BackgroundJobDAO.create(job)
      stmt.id
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[BankStatementView]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.find(id, userId)
    }
  }

  def all(userId: UUID): Future[List[BankStatementView]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.all(userId)
    }
  }

  def setAiSuggestions(id: UUID, userId: UUID, suggestions: AiSuggestions, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.setAiSuggestions(id, userId, suggestions, updatedAt)
    }
  }

  /** Mark the statement's suggestions as pending and enqueue the job that recomputes them, in one transaction. The
    * suggestions are written first so a missing or foreign statement fails before the job is created.
    */
  def requestAiSuggestions(
      id: UUID,
      userId: UUID,
      pending: AiSuggestions,
      job: BackgroundJobData.Create,
      updatedAt: Instant
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      BankStatementDAO.setAiSuggestions(id, userId, pending, updatedAt)
      BackgroundJobDAO.create(job)
    }
  }

  def findInternal(id: UUID): Future[Option[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findInternal(id)
    }
  }

  def findByDocumentId(documentId: UUID, userId: UUID): Future[Option[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findByDocumentId(documentId, userId)
    }
  }

  def findByDocumentIdInternal(documentId: UUID): Future[Option[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findByDocumentIdInternal(documentId)
    }
  }

  def createRows(rows: List[BankStatementRow.Create]): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      BankStatementRowDAO.createAll(rows)
    }
  }

  def allRows(userId: UUID): Future[List[BankStatementRow]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.allForUser(userId)
    }
  }

  def categorizedRowExamples(userId: UUID, perCategory: Int): Future[List[(UUID, String)]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.categorizedExamples(userId, perCategory)
    }
  }

  def setRowExpenseIdForUser(rowId: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.setExpenseIdForUser(rowId, userId, expenseId, updatedAt)
    }
  }

  def findRows(bankStatementId: UUID): Future[List[BankStatementRow]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.findByStatementId(bankStatementId)
    }
  }

  def findRow(rowId: UUID, bankStatementId: UUID): Future[Option[BankStatementRow]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.findById(rowId, bankStatementId)
    }
  }

  def setRowExpenseId(stmtId: UUID, rowId: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant): Future[Unit] =
    Future {
      database.withTransaction { implicit conn =>
        val stmtOpt = BankStatementDAO.find(stmtId, userId)
        if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
        BankStatementRowDAO.setExpenseId(rowId, stmtId, expenseId, updatedAt)
      }
    }

  def clearRowExpenseId(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(stmtId, userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearExpenseId(rowId, stmtId, updatedAt)
    }
  }

  def setRowRefundId(stmtId: UUID, rowId: UUID, userId: UUID, refundId: UUID, updatedAt: Instant): Future[Unit] =
    Future {
      database.withTransaction { implicit conn =>
        val stmtOpt = BankStatementDAO.find(stmtId, userId)
        if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
        BankStatementRowDAO.setRefundId(rowId, stmtId, refundId, updatedAt)
      }
    }

  def clearRowRefundId(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(stmtId, userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearRefundId(rowId, stmtId, updatedAt)
    }
  }

  def ignoreRow(stmtId: UUID, rowId: UUID, userId: UUID, ignoredAt: Instant, updatedAt: Instant): Future[Unit] =
    Future {
      database.withTransaction { implicit conn =>
        val stmtOpt = BankStatementDAO.find(stmtId, userId)
        if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
        BankStatementRowDAO.setIgnoredAt(rowId, stmtId, ignoredAt, updatedAt)
      }
    }

  def unignoreRow(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(stmtId, userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearIgnoredAt(rowId, stmtId, updatedAt)
    }
  }

  def confirmRow(
      rowId: UUID,
      bankStatementId: UUID,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      manuallyEdited: Boolean,
      confirmedAt: Instant,
      updatedAt: Instant
  ): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.confirm(
        rowId,
        bankStatementId,
        date,
        amount,
        description,
        manuallyEdited,
        confirmedAt,
        updatedAt
      )
    }
  }
}
