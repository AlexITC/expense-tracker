package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import io.scalaland.chimney.dsl.*
import net.wiringbits.api.models.bankstatements.CreateExpenseFromRow
import net.wiringbits.actions.expenses.ExpenseValidator
import net.wiringbits.repositories.models.{Expense, ExpenseCategorySplit}
import net.wiringbits.repositories.{BankStatementRepository, ExpensesRepository, ReceiptRepository}
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class CreateExpenseFromRowAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    receiptRepository: ReceiptRepository,
    expensesRepository: ExpensesRepository,
    validator: ExpenseValidator
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      stmtId: UUID,
      rowId: UUID,
      userId: UUID,
      request: CreateExpenseFromRow.Request
  ): Future[CreateExpenseFromRow.Response] = {
    logger.info(s"Create expense from row: stmtId=$stmtId rowId=$rowId userId=$userId amount=${request.amount}")
    for {
      rowOpt <- bankStatementRepository.findRow(rowId, stmtId)
      row = rowOpt.getOrElse(throw new RuntimeException("Bank statement row not found"))
      _ <- bankStatementRepository.find(stmtId, userId).map {
        case None => throw new RuntimeException("Bank statement not found")
        case _ => ()
      }
      _ = if (row.amount >= 0) throw new RuntimeException("Only debit rows (amount < 0) can be linked to expenses")
      _ = if (row.expenseId.isDefined) throw new RuntimeException("Row is already linked to an expense — unlink first")
      _ <- validator.validate(
        userId,
        request.amount,
        request.notes,
        request.merchantId,
        request.splits.map(s => (s.categoryId, s.amount))
      )
      _ <- expensesRepository
        .countByMonth(userId, request.date.getYear, request.date.getMonthValue)
        .flatMap { count =>
          if (count >= 500)
            Future.failed(new RuntimeException("Maximum number of expenses for this month (500) reached"))
          else Future.unit
        }
      now = clock.instant()
      expenseId = UuidCreator.getTimeOrderedEpoch()
      expense = Expense.Create(
        id = expenseId,
        userId = userId,
        amount = request.amount,
        date = request.date,
        time = request.time,
        merchantId = request.merchantId,
        notes = request.notes,
        createdAt = now,
        updatedAt = now
      )
      splits = request.splits.map { s =>
        ExpenseCategorySplit.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          expenseId = expenseId,
          categoryId = s.categoryId,
          amount = s.amount
        )
      }
      _ <- expensesRepository.create(expense, splits)
      _ <- bankStatementRepository.setRowExpenseId(stmtId, rowId, userId, expenseId, clock.instant())
      _ <- request.receiptId match {
        case None => Future.unit
        case Some(rId) => receiptRepository.linkToExpense(rId, userId, expenseId)
      }
    } yield CreateExpenseFromRow.Response(expenseId = expenseId)
  }
}
