package net.wiringbits.actions.refunds

import net.wiringbits.api.models.refunds.UnlinkRefundFromExpense
import net.wiringbits.repositories.RefundsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnlinkRefundFromExpenseAction @Inject() (refundsRepository: RefundsRepository)(implicit ec: ExecutionContext) {

  def apply(refundId: UUID, userId: UUID): Future[UnlinkRefundFromExpense.Response] =
    for {
      _ <- refundsRepository.unlinkFromExpense(refundId, userId)
    } yield UnlinkRefundFromExpense.Response()
}
