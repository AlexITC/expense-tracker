package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.LinkReceiptToExpense
import net.wiringbits.repositories.{ExpensesRepository, ReceiptRepository}

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LinkReceiptToExpenseAction @Inject() (
    receiptRepository: ReceiptRepository,
    expensesRepository: ExpensesRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(
      receiptId: UUID,
      userId: UUID,
      request: LinkReceiptToExpense.Request
  ): Future[LinkReceiptToExpense.Response] =
    for {
      receiptOpt <- receiptRepository.find(id = receiptId, userId = userId)
      _ = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      expenseOpt <- expensesRepository.find(id = request.expenseId, userId = userId)
      _ = if (expenseOpt.isEmpty) throw new RuntimeException("Expense not found")
      _ <- receiptRepository.linkToExpense(id = receiptId, userId = userId, expenseId = request.expenseId)
    } yield LinkReceiptToExpense.Response()
}
