package net.wiringbits.repositories
import net.wiringbits.repositories.support.ConstraintViolations

import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.repositories.daos.{
  BankStatementRowDAO,
  DocumentDAO,
  ExpenseCategorySplitDAO,
  ExpenseDAO,
  ReceiptDAO,
  RefundDAO
}
import net.wiringbits.api.models.expenses.{MergeDetailsSource, MergeShape}
import net.wiringbits.common.ExpenseSplits
import net.wiringbits.repositories.ExpensesRepository.{MergeError, MergePlan, ValidatedMerge}
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

  /** Merges the loser into the row-linked survivor, moving the loser's refunds to the survivor and deleting the loser.
    * The expenses must still have the `shape` the client detected:
    *   - Receipt: the loser's receipt moves to the survivor.
    *   - Orphan: the loser has no receipt, so only the row's details can be kept.
    *   - ReceiptOnBoth: the survivor keeps its own receipt and details; the loser's receipt and its document are
    *     deleted.
    *
    * Returns the storage key of the discarded document (ReceiptOnBoth only) so the caller can delete the blob once the
    * transaction has committed.
    */
  def merge(
      survivorId: UUID,
      loserId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource,
      shape: MergeShape
  ): Future[Option[String]] = Future {
    ConstraintViolations.translating(ConstraintViolations.ReceiptExpenseIdUnique) {
      database.withTransaction { implicit conn =>
        val validated =
          validateMerge(
            survivorId = survivorId,
            loserId = loserId,
            userId = userId,
            keepDetailsFrom = keepDetailsFrom,
            shape = shape
          ).fold(e => throw new RuntimeException(e.message), identity)
        val now = clock.instant()
        keepDetailsFrom match {
          case MergeDetailsSource.Row => ()
          case MergeDetailsSource.Receipt =>
            adoptLoserDetails(survivor = validated.survivor, loser = validated.loser, now = now)
        }
        validated.plan match {
          case MergePlan.MoveReceipt(receipt) =>
            relinkReceipt(receipt = receipt, survivorId = survivorId, userId = userId, now = now)
          case MergePlan.Orphan | MergePlan.DiscardLoserReceipt(_) => ()
        }
        RefundDAO.reassignExpense(fromExpenseId = loserId, toExpenseId = survivorId, userId = userId, now)
        ExpenseDAO.delete(id = loserId, userId = userId)
        validated.plan match {
          case MergePlan.DiscardLoserReceipt(receipt) =>
            Some(discardReceiptDocument(receipt = receipt, userId = userId))
          case MergePlan.MoveReceipt(_) | MergePlan.Orphan => None
        }
      }
    }
  }

  /** Deletes the receipt's document, which cascades to the receipt, and returns the document's storage key. */
  private def discardReceiptDocument(receipt: Receipt, userId: UUID)(implicit conn: Connection): String = {
    val document = DocumentDAO
      .find(id = receipt.documentId, userId = userId)
      .getOrElse(throw new RuntimeException("Document not found"))
    DocumentDAO.delete(id = document.id, userId = userId)
    document.storageKey
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

  /** Checks the merge guards and returns both expenses plus what happens to the loser's receipt. */
  private def validateMerge(
      survivorId: UUID,
      loserId: UUID,
      userId: UUID,
      keepDetailsFrom: MergeDetailsSource,
      shape: MergeShape
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
      plan <- checkReceiptGuards(
        loserReceipt = ReceiptDAO.findByExpenseId(expenseId = loserId, userId = userId),
        survivorHasReceipt = ReceiptDAO.findByExpenseId(expenseId = survivorId, userId = userId).isDefined,
        keepDetailsFrom = keepDetailsFrom,
        shape = shape
      )
    } yield ValidatedMerge(survivor = survivor, loser = loser, plan = plan)
  }

  /** The actual shape must match the one the client detected. An orphan cannot donate its details, and a
    * receipt-on-both merge keeps the survivor's details and receipt, so the loser's receipt must not back a refund.
    */
  private def checkReceiptGuards(
      loserReceipt: Option[Receipt],
      survivorHasReceipt: Boolean,
      keepDetailsFrom: MergeDetailsSource,
      shape: MergeShape
  ): Either[MergeError, MergePlan] = {
    import MergeError.*
    val (actualShape, plan) = loserReceipt match {
      case None => (MergeShape.Orphan, MergePlan.Orphan)
      case Some(receipt) if survivorHasReceipt => (MergeShape.ReceiptOnBoth, MergePlan.DiscardLoserReceipt(receipt))
      case Some(receipt) => (MergeShape.Receipt, MergePlan.MoveReceipt(receipt))
    }
    for {
      _ <- Either.cond(actualShape == shape, (), ShapeChanged)
      _ <- plan match {
        case MergePlan.Orphan => Either.cond(keepDetailsFrom == MergeDetailsSource.Row, (), OrphanDetailsNotSupported)
        case MergePlan.DiscardLoserReceipt(receipt) =>
          for {
            _ <- Either.cond(keepDetailsFrom == MergeDetailsSource.Row, (), ReceiptOnBothDetailsNotSupported)
            _ <- Either.cond(receipt.refundId.isEmpty, (), LoserReceiptLinkedToRefund)
          } yield ()
        case MergePlan.MoveReceipt(_) => Right(())
      }
    } yield plan
  }

  private def relinkReceipt(receipt: Receipt, survivorId: UUID, userId: UUID, now: Instant)(implicit
      conn: Connection
  ): Unit = {
    ReceiptDAO.clearExpenseId(id = receipt.id, userId = userId, now)
    ReceiptDAO.setExpenseId(id = receipt.id, userId = userId, expenseId = survivorId, now)
  }
}

object ExpensesRepository {

  private final case class ValidatedMerge(survivor: Expense, loser: Expense, plan: MergePlan)

  /** What happens to the loser's receipt. */
  private enum MergePlan {
    case MoveReceipt(receipt: Receipt)
    case Orphan
    case DiscardLoserReceipt(receipt: Receipt)
  }

  enum MergeError(val message: String) {
    case SelfMerge extends MergeError("Cannot merge an expense with itself")
    case ExpenseNotFound extends MergeError("Expense not found")
    case SurvivorWithoutRow extends MergeError("The surviving expense must be linked to a bank statement row")
    case LoserWithRow extends MergeError("The merged expense must not be linked to a bank statement row")
    case ShapeChanged extends MergeError("The expenses changed since the duplicate was detected; refresh and try again")
    case OrphanDetailsNotSupported extends MergeError("Cannot keep the duplicate's details because it has no receipt")
    case ReceiptOnBothDetailsNotSupported
        extends MergeError("Cannot keep the duplicate's details when both expenses have a receipt")
    case LoserReceiptLinkedToRefund
        extends MergeError("The duplicate's receipt is linked to a refund — unlink it first")
    case AmountMismatch extends MergeError("Cannot keep the receipt's details because the expense amounts differ")
    case LoserSplitsNotFullyAllocated
        extends MergeError("Cannot keep the receipt's details because its category splits do not sum to the amount")
  }
}
