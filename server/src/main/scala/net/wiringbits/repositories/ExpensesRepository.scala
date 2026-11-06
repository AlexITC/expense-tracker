package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{
  BankStatementRowDAO,
  ExpenseCategorySplitDAO,
  ExpenseDAO,
  ReceiptDAO,
  RefundDAO
}
import net.wiringbits.repositories.ExpensesRepository.MergeError
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit, Receipt}
import play.api.db.Database

import java.sql.Connection
import java.time.{Clock, Instant, LocalDate, LocalTime}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class ExpensesRepository @Inject() (database: Database)(implicit
    ec: DatabaseExecutionContext,
    clock: Clock
) {

  def create(expense: Expense.Create, splits: List[ExpenseCategorySplit.Create]): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ExpenseDAO.create(expense)
      ExpenseCategorySplitDAO.createAll(splits)
    }
  }

  def all(
      userId: UUID,
      from: Option[LocalDate],
      to: Option[LocalDate],
      unlinkedOnly: Boolean
  ): Future[List[Expense]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.all(userId, from, to, unlinkedOnly)
    }
  }

  def allSplits(userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByUserId(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Expense]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.find(id, userId)
    }
  }

  def update(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      splits: List[ExpenseCategorySplit.Create]
  ): Future[Unit] = Future {
    database.withTransaction { implicit conn =>
      ExpenseDAO.update(id, userId, amount, date, time, merchantId, notes, clock.instant())
      ExpenseCategorySplitDAO.deleteByExpenseId(id, userId)
      ExpenseCategorySplitDAO.createAll(splits)
    }
  }

  def countByMonth(userId: UUID, year: Int, month: Int): Future[Long] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.countByMonth(userId, year, month)
    }
  }

  def findSplitsByExpenseId(expenseId: UUID, userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByExpenseId(expenseId, userId)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.delete(id, userId)
    }
  }

  def merge(survivorId: UUID, loserId: UUID, userId: UUID): Future[Unit] = Future {
    ConstraintViolations.translating(ReceiptRepository.ReceiptExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        val receipt =
          validateMerge(survivorId, loserId, userId).fold(e => throw new RuntimeException(e.message), identity)
        val now = clock.instant()
        relinkReceipt(receipt, survivorId, userId, now)
        RefundDAO.reassignExpense(loserId, survivorId, userId, now)
        ExpenseDAO.delete(loserId, userId)
      }
    }
  }

  /** Checks the merge guards and returns the loser's receipt, which moves to the survivor. */
  private def validateMerge(survivorId: UUID, loserId: UUID, userId: UUID)(implicit
      conn: Connection
  ): Either[MergeError, Receipt] = {
    import MergeError.*
    for {
      _ <- Either.cond(survivorId != loserId, (), SelfMerge)
      _ <- Either.cond(
        ExpenseDAO.find(survivorId, userId).isDefined && ExpenseDAO.find(loserId, userId).isDefined,
        (),
        ExpenseNotFound
      )
      _ <- Either.cond(BankStatementRowDAO.existsForExpense(survivorId, userId), (), SurvivorWithoutRow)
      _ <- Either.cond(!BankStatementRowDAO.existsForExpense(loserId, userId), (), LoserWithRow)
      receipt <- ReceiptDAO.findByExpenseId(loserId, userId).toRight(LoserWithoutReceipt)
      _ <- Either.cond(ReceiptDAO.findByExpenseId(survivorId, userId).isEmpty, (), SurvivorWithReceipt)
    } yield receipt
  }

  private def relinkReceipt(receipt: Receipt, survivorId: UUID, userId: UUID, now: Instant)(implicit
      conn: Connection
  ): Unit = {
    ReceiptDAO.clearExpenseId(receipt.id, userId, now)
    ReceiptDAO.setExpenseId(receipt.id, userId, survivorId, now)
  }
}

object ExpensesRepository {

  enum MergeError(val message: String) {
    case SelfMerge extends MergeError("Cannot merge an expense with itself")
    case ExpenseNotFound extends MergeError("Expense not found")
    case SurvivorWithoutRow extends MergeError("The surviving expense must be linked to a bank statement row")
    case LoserWithRow extends MergeError("The merged expense must not be linked to a bank statement row")
    case LoserWithoutReceipt extends MergeError("The merged expense must have a receipt")
    case SurvivorWithReceipt extends MergeError("The surviving expense already has a receipt")
  }
}
