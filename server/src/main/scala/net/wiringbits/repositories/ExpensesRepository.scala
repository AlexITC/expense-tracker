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
      ExpenseDAO.all(userId, from = from, to = to, unlinkedOnly)
    }
  }

  def allSplits(userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByUserId(userId)
    }
  }

  def find(id: UUID, userId: UUID): Future[Option[Expense]] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.find(id = id, userId = userId)
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
      ExpenseDAO.update(id = id, userId = userId, amount, date, time, merchantId, notes, clock.instant())
      ExpenseCategorySplitDAO.deleteByExpenseId(expenseId = id, userId = userId)
      ExpenseCategorySplitDAO.createAll(splits)
    }
  }

  def countByMonth(userId: UUID, year: Int, month: Int): Future[Long] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.countByMonth(userId, year = year, month = month)
    }
  }

  def findSplitsByExpenseId(expenseId: UUID, userId: UUID): Future[List[ExpenseCategorySplit]] = Future {
    database.withConnection { implicit conn =>
      ExpenseCategorySplitDAO.findByExpenseId(expenseId = expenseId, userId = userId)
    }
  }

  def delete(id: UUID, userId: UUID): Future[Unit] = Future {
    database.withConnection { implicit conn =>
      ExpenseDAO.delete(id = id, userId = userId)
    }
  }

  /** Merges the loser into the row-linked survivor: moves the loser's receipt (if any) and refunds to the survivor,
    * then deletes the loser. A loser without a receipt (an orphan duplicate) can only be merged keeping the row's
    * details.
    */
  def merge(survivorId: UUID, loserId: UUID, userId: UUID, keepDetailsFrom: MergeDetailsSource): Future[Unit] = Future {
    ConstraintViolations.translating(ReceiptRepository.ReceiptExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        val validated =
          validateMerge(survivorId = survivorId, loserId = loserId, userId = userId, keepDetailsFrom = keepDetailsFrom)
            .fold(e => throw new RuntimeException(e.message), identity)
        val now = clock.instant()
        keepDetailsFrom match {
          case MergeDetailsSource.Row => ()
          case MergeDetailsSource.Receipt =>
            adoptLoserDetails(survivor = validated.survivor, loser = validated.loser, now = now)
        }
        validated.receipt.foreach(receipt =>
          relinkReceipt(receipt = receipt, survivorId = survivorId, userId = userId, now = now)
        )
        RefundDAO.reassignExpense(fromExpenseId = loserId, toExpenseId = survivorId, userId = userId, now)
        ExpenseDAO.delete(id = loserId, userId = userId)
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

  /** Checks the merge guards and returns both expenses plus the loser's receipt, if any, which moves to the survivor.
    * The survivor must not already hold a receipt only when the loser brings one; an orphan loser cannot donate its
    * details.
    */
  private def validateMerge(
      survivorId: UUID,
      loserId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource
  )(implicit
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
      _ <- Either.cond(
        BankStatementRowDAO.existsForExpense(expenseId = survivorId, userId = userId),
        (),
        SurvivorWithoutRow
      )
      _ <- Either.cond(!BankStatementRowDAO.existsForExpense(expenseId = loserId, userId = userId), (), LoserWithRow)
      receipt = ReceiptDAO.findByExpenseId(expenseId = loserId, userId = userId)
      _ <- checkReceiptGuards(
        loserReceipt = receipt,
        survivorId = survivorId,
        userId = userId,
        keepDetailsFrom = keepDetailsFrom
      )
    } yield ValidatedMerge(survivor = survivor, loser = loser, receipt = receipt)
  }

  /** A loser with a receipt needs a receipt-free survivor; a loser without one cannot donate its details. */
  private def checkReceiptGuards(
      loserReceipt: Option[Receipt],
      survivorId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource
  )(implicit conn: Connection): Either[MergeError, Unit] = {
    import MergeError.*
    loserReceipt match {
      case Some(_) =>
        Either.cond(
          ReceiptDAO.findByExpenseId(expenseId = survivorId, userId = userId).isEmpty,
          (),
          SurvivorWithReceipt
        )
      case None => Either.cond(keepDetailsFrom == MergeDetailsSource.Row, (), OrphanDetailsNotSupported)
    }
  }

  private def relinkReceipt(receipt: Receipt, survivorId: UUID, userId: UUID, now: Instant)(implicit
      conn: Connection
  ): Unit = {
    ReceiptDAO.clearExpenseId(id = receipt.id, userId = userId, now)
    ReceiptDAO.setExpenseId(id = receipt.id, userId = userId, expenseId = survivorId, now)
  }
}

object ExpensesRepository {

  private final case class ValidatedMerge(survivor: Expense, loser: Expense, receipt: Option[Receipt])

  enum MergeError(val message: String) {
    case SelfMerge extends MergeError("Cannot merge an expense with itself")
    case ExpenseNotFound extends MergeError("Expense not found")
    case SurvivorWithoutRow extends MergeError("The surviving expense must be linked to a bank statement row")
    case LoserWithRow extends MergeError("The merged expense must not be linked to a bank statement row")
    case SurvivorWithReceipt extends MergeError("The surviving expense already has a receipt")
    case OrphanDetailsNotSupported extends MergeError("Cannot keep the duplicate's details because it has no receipt")
    case AmountMismatch extends MergeError("Cannot keep the receipt's details because the expense amounts differ")
    case LoserSplitsNotFullyAllocated
        extends MergeError("Cannot keep the receipt's details because its category splits do not sum to the amount")
  }
}
