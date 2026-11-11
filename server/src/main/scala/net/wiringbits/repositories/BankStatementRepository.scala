package net.wiringbits.repositories
import net.wiringbits.repositories.support.ConstraintViolations

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.{AiSuggestions, ExtractionInfo}
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{
  BackgroundJobDAO,
  BankAccountAliasDAO,
  BankStatementDAO,
  BankStatementRowDAO,
  DocumentDAO,
  ExpenseDAO
}
import net.wiringbits.repositories.models.{
  BackgroundJobData,
  BankAccountAlias,
  BankStatement,
  BankStatementRow,
  BankStatementRowLinkedExpense,
  BankStatementView
}
import play.api.db.Database

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class BankStatementRepository @Inject() (database: Database)(implicit ec: DatabaseExecutionContext) {
  import BankStatementRepository._

  /** Confirm a draft import: mark the draft's existing document Parsed and create the statement (with metadata) + rows
    * in one transaction, and enqueue the given job (the AI suggestions job; the rows are already extracted). Used by
    * the AI draft-import flow.
    */
  def createConfirmedStatement(
      stmt: BankStatement.Create,
      metadata: BankStatement.Metadata,
      rows: List[BankStatementRow.Create],
      extractionInfo: Option[ExtractionInfo],
      job: BackgroundJobData.Create
  ): Future[UUID] = Future {
    database.withTransaction { implicit conn =>
      // The confirmed statement must reference a document owned by the same user.
      if (DocumentDAO.find(id = stmt.documentId, userId = stmt.userId).isEmpty)
        throw new RuntimeException("Document not found")
      BankStatementDAO.create(stmt, metadata, extractionInfo)
      BankAccountAliasDAO.ensureExists(
        id = UuidCreator.getTimeOrderedEpoch(),
        userId = stmt.userId,
        key = metadata.transformInto[BankAccountAlias.Key],
        now = stmt.createdAt
      )
      BankStatementRowDAO.createAll(rows)
      BackgroundJobDAO.create(job)
      stmt.id
    }
  }

  /** Names the account the given statement belongs to; the statement lookup is what proves the user owns it. */
  def setAccountAlias(bankStatementId: UUID, userId: UUID, alias: String, now: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmt = BankStatementDAO
        .find(id = bankStatementId, userId = userId)
        .getOrElse(throw new RuntimeException("Bank statement not found"))
      BankAccountAliasDAO.upsert(
        id = UuidCreator.getTimeOrderedEpoch(),
        userId = userId,
        key = stmt.transformInto[BankAccountAlias.Key],
        alias = alias,
        now = now
      )
    }
  }

  def listAccountAliases(userId: UUID): Future[List[BankAccountAlias]] = Future {
    database.withConnection { implicit conn =>
      BankAccountAliasDAO.listForUser(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[BankStatementView]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.find(id = id, userId = userId)
    }
  }

  def all(userId: UUID): Future[List[BankStatementView]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.all(userId)
    }
  }

  def setAiSuggestions(id: UUID, userId: UUID, suggestions: AiSuggestions, updatedAt: Instant): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.setAiSuggestions(id = id, userId = userId, suggestions, updatedAt)
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
      BankStatementDAO.setAiSuggestions(id = id, userId = userId, pending, updatedAt)
      BackgroundJobDAO.create(job)
    }
  }

  def findOverlapping(userId: UUID, metadata: BankStatement.Metadata): Future[List[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findOverlapping(userId, metadata)
    }
  }

  def overlappingStatementIds(userId: UUID): Future[Map[UUID, List[UUID]]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.overlappingStatementIds(userId)
    }
  }

  def findInternal(id: UUID): Future[Option[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findInternal(id)
    }
  }

  def findByDocumentId(documentId: UUID, userId: UUID): Future[Option[BankStatement]] = Future {
    database.withConnection { implicit conn =>
      BankStatementDAO.findByDocumentId(documentId = documentId, userId = userId)
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

  def categoryHistory(userId: UUID): Future[Map[String, UUID]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.categoryHistory(userId)
    }
  }

  def setRowExpenseIdForUser(rowId: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant): Future[Unit] = Future {
    ConstraintViolations.translating(RowExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        if (ExpenseDAO.find(id = expenseId, userId = userId).isEmpty) throw new RuntimeException("Expense not found")
        BankStatementRowDAO.setExpenseIdForUser(rowId = rowId, userId = userId, expenseId = expenseId, updatedAt)
      }
    }
  }

  def findRows(bankStatementId: UUID): Future[List[BankStatementRow]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.findByStatementId(bankStatementId)
    }
  }

  def findRowLinkedExpenses(bankStatementId: UUID, userId: UUID): Future[List[BankStatementRowLinkedExpense]] =
    Future {
      database.withConnection { implicit conn =>
        BankStatementRowDAO.linkedExpenses(bankStatementId = bankStatementId, userId = userId)
      }
    }

  def findRow(rowId: UUID, bankStatementId: UUID): Future[Option[BankStatementRow]] = Future {
    database.withConnection { implicit conn =>
      BankStatementRowDAO.findById(rowId = rowId, bankStatementId = bankStatementId)
    }
  }

  def setRowExpenseId(stmtId: UUID, rowId: UUID, userId: UUID, expenseId: UUID, updatedAt: Instant): Future[Unit] =
    Future {
      ConstraintViolations.translating(RowExpenseIdUnique) {
        database.withTransaction { implicit conn =>
          val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
          if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
          if (ExpenseDAO.find(id = expenseId, userId = userId).isEmpty) throw new RuntimeException("Expense not found")
          BankStatementRowDAO.setExpenseId(rowId = rowId, bankStatementId = stmtId, expenseId = expenseId, updatedAt)
        }
      }
    }

  def clearRowExpenseId(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearExpenseId(rowId = rowId, bankStatementId = stmtId, updatedAt)
    }
  }

  def setRowRefundId(stmtId: UUID, rowId: UUID, userId: UUID, refundId: UUID, updatedAt: Instant): Future[Unit] =
    Future {
      database.withTransaction { implicit conn =>
        val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
        if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
        BankStatementRowDAO.setRefundId(rowId = rowId, bankStatementId = stmtId, refundId = refundId, updatedAt)
      }
    }

  def clearRowRefundId(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearRefundId(rowId = rowId, bankStatementId = stmtId, updatedAt)
    }
  }

  def ignoreRow(stmtId: UUID, rowId: UUID, userId: UUID, ignoredAt: Instant, updatedAt: Instant): Future[Unit] =
    Future {
      database.withTransaction { implicit conn =>
        val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
        if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
        BankStatementRowDAO.setIgnoredAt(
          rowId = rowId,
          bankStatementId = stmtId,
          ignoredAt = ignoredAt,
          updatedAt = updatedAt
        )
      }
    }

  def unignoreRow(stmtId: UUID, rowId: UUID, userId: UUID, updatedAt: Instant): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      val stmtOpt = BankStatementDAO.find(id = stmtId, userId = userId)
      if (stmtOpt.isEmpty) throw new RuntimeException("Bank statement not found")
      BankStatementRowDAO.clearIgnoredAt(rowId = rowId, bankStatementId = stmtId, updatedAt)
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
        rowId = rowId,
        bankStatementId = bankStatementId,
        date,
        amount,
        description,
        manuallyEdited,
        confirmedAt = confirmedAt,
        updatedAt = updatedAt
      )
    }
  }
}

object BankStatementRepository {
  val RowExpenseIdUnique: (String, String) =
    "bank_statement_row_expense_id_unique" -> "That expense is already linked to another row"
}
