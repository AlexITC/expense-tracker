package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.LinkRowToExpense
import net.wiringbits.repositories.{BankStatementRepository, ExpensesRepository}
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LinkRowToExpenseAction @Inject() (
    bankStatementRepository: BankStatementRepository,
    expensesRepository: ExpensesRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      stmtId: UUID,
      rowId: UUID,
      userId: UUID,
      request: LinkRowToExpense.Request
  ): Future[LinkRowToExpense.Response] = {
    logger.info(s"Link row to expense: stmtId=$stmtId rowId=$rowId expenseId=${request.expenseId} userId=$userId")
    for {
      rowOpt <- bankStatementRepository.findRow(rowId, stmtId)
      row = rowOpt.getOrElse(throw new RuntimeException("Bank statement row not found"))
      _ <- bankStatementRepository.find(stmtId, userId).map {
        case None => throw new RuntimeException("Bank statement not found")
        case _ => ()
      }
      _ = if (row.amount >= 0) throw new RuntimeException("Only debit rows (amount < 0) can be linked to expenses")
      _ = if (row.expenseId.isDefined) throw new RuntimeException("Row is already linked to an expense — unlink first")
      _ = if (row.ignoredAt.isDefined) throw new RuntimeException("Row is ignored — unignore first")
      expenseOpt <- expensesRepository.find(request.expenseId, userId)
      _ = if (expenseOpt.isEmpty) throw new RuntimeException("Expense not found")
      _ <- bankStatementRepository.setRowExpenseId(stmtId, rowId, userId, request.expenseId, clock.instant())
    } yield LinkRowToExpense.Response()
  }
}
