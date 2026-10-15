package net.wiringbits.actions.refunds

import net.wiringbits.api.models.refunds.LinkRefundToExpense
import net.wiringbits.repositories.{ExpensesRepository, RefundsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LinkRefundToExpenseAction @Inject() (
    refundsRepository: RefundsRepository,
    expensesRepository: ExpensesRepository
)(implicit ec: ExecutionContext) {

  def apply(
      refundId: UUID,
      userId: UUID,
      request: LinkRefundToExpense.Request
  ): Future[LinkRefundToExpense.Response] =
    for {
      refundOpt <- refundsRepository.find(refundId, userId)
      _ = if (refundOpt.isEmpty) throw new RuntimeException("Refund not found")
      expenseOpt <- expensesRepository.find(request.expenseId, userId)
      _ = if (expenseOpt.isEmpty) throw new RuntimeException("Expense not found")
      _ <- refundsRepository.linkToExpense(refundId, userId, request.expenseId)
    } yield LinkRefundToExpense.Response()
}
