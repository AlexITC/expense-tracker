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
      receiptOpt <- receiptRepository.find(receiptId, userId)
      _ = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      expenseOpt <- expensesRepository.find(request.expenseId, userId)
      _ = if (expenseOpt.isEmpty) throw new RuntimeException("Expense not found")
      _ <- receiptRepository.linkToExpense(receiptId, userId, request.expenseId)
    } yield LinkReceiptToExpense.Response()
}
