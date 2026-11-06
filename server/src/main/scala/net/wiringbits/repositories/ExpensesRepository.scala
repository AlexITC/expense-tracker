package net.wiringbits.repositories

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{
  BankStatementRowDAO,
  ExpenseCategorySplitDAO,
  ExpenseDAO,
  ReceiptDAO,
  RefundDAO
}
import net.wiringbits.api.models.expenses.MergeDetailsSource
import net.wiringbits.common.ExpenseSplits
import net.wiringbits.repositories.ExpensesRepository.{MergeError, ValidatedMerge}
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

  def merge(survivorId: UUID, loserId: UUID, userId: UUID, keepDetailsFrom: MergeDetailsSource): Future[Unit] = Future {
    ConstraintViolations.translating(ReceiptRepository.ReceiptExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        val validated =
          validateMerge(survivorId = survivorId, loserId = loserId, userId = userId)
            .fold(e => throw new RuntimeException(e.message), identity)
        val now = clock.instant()
        keepDetailsFrom match {
          case MergeDetailsSource.Row => ()
          case MergeDetailsSource.Receipt =>
            adoptLoserDetails(survivor = validated.survivor, loser = validated.loser, now = now)
        }
        relinkReceipt(receipt = validated.receipt, survivorId = survivorId, userId = userId, now = now)
        RefundDAO.reassignExpense(loserId, survivorId, userId, now)
        ExpenseDAO.delete(loserId, userId)
      }
    }
  }

  /** Copies the loser's merchant, notes and splits onto the survivor, keeping the survivor's amount, date and time. */
  private def adoptLoserDetails(survivor: Expense, loser: Expense, now: Instant)(implicit conn: Connection): Unit = {
    import MergeError.*
    val loserSplits = ExpenseCategorySplitDAO.findByExpenseId(expenseId = loser.id, userId = loser.userId)
    val result = for {
      _ <- Either.cond(survivor.amount == loser.amount, (), AmountMismatch)
      _ <- Either.cond(
        ExpenseSplits.isFullyAllocated(survivor.amount, loserSplits.map(_.amount)),
        (),
        LoserSplitsNotFullyAllocated
      )
    } yield ()
    result.fold(e => throw new RuntimeException(e.message), identity)

    ExpenseDAO.update(
      id = survivor.id,
      userId = survivor.userId,
      amount = survivor.amount,
      date = survivor.date,
      time = survivor.time,
      merchantId = loser.merchantId,
      notes = loser.notes,
      updatedAt = now
    )
    ExpenseCategorySplitDAO.deleteByExpenseId(expenseId = survivor.id, userId = survivor.userId)
    ExpenseCategorySplitDAO.createAll(
      loserSplits.map(s =>
        ExpenseCategorySplit.Create(
          id = UUID.randomUUID(),
          userId = survivor.userId,
          expenseId = survivor.id,
          categoryId = s.categoryId,
          amount = s.amount
        )
      )
    )
  }

  /** Checks the merge guards and returns both expenses plus the loser's receipt, which moves to the survivor. */
  private def validateMerge(survivorId: UUID, loserId: UUID, userId: UUID)(implicit
      conn: Connection
  ): Either[MergeError, ValidatedMerge] = {
    import MergeError.*
    for {
      _ <- Either.cond(survivorId != loserId, (), SelfMerge)
      expenses <- ExpenseDAO
        .find(id = survivorId, userId = userId)
        .zip(ExpenseDAO.find(id = loserId, userId = userId))
        .toRight(ExpenseNotFound)
      (survivor, loser) = expenses
      _ <- Either.cond(BankStatementRowDAO.existsForExpense(survivorId, userId), (), SurvivorWithoutRow)
      _ <- Either.cond(!BankStatementRowDAO.existsForExpense(loserId, userId), (), LoserWithRow)
      receipt <- ReceiptDAO.findByExpenseId(loserId, userId).toRight(LoserWithoutReceipt)
      _ <- Either.cond(ReceiptDAO.findByExpenseId(survivorId, userId).isEmpty, (), SurvivorWithReceipt)
    } yield ValidatedMerge(survivor = survivor, loser = loser, receipt = receipt)
  }

  private def relinkReceipt(receipt: Receipt, survivorId: UUID, userId: UUID, now: Instant)(implicit
      conn: Connection
  ): Unit = {
    ReceiptDAO.clearExpenseId(receipt.id, userId, now)
    ReceiptDAO.setExpenseId(receipt.id, userId, survivorId, now)
  }
}

object ExpensesRepository {

  private final case class ValidatedMerge(survivor: Expense, loser: Expense, receipt: Receipt)

  enum MergeError(val message: String) {
    case SelfMerge extends MergeError("Cannot merge an expense with itself")
    case ExpenseNotFound extends MergeError("Expense not found")
    case SurvivorWithoutRow extends MergeError("The surviving expense must be linked to a bank statement row")
    case LoserWithRow extends MergeError("The merged expense must not be linked to a bank statement row")
    case LoserWithoutReceipt extends MergeError("The merged expense must have a receipt")
    case SurvivorWithReceipt extends MergeError("The surviving expense already has a receipt")
    case AmountMismatch extends MergeError("Cannot keep the receipt's details because the expense amounts differ")
    case LoserSplitsNotFullyAllocated
        extends MergeError("Cannot keep the receipt's details because its category splits do not sum to the amount")
  }
}
