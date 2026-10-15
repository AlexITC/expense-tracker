package net.wiringbits.actions.refunds

import net.wiringbits.api.models.refunds.DeleteRefund
import net.wiringbits.repositories.RefundsRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteRefundAction @Inject() (refundsRepository: RefundsRepository)(implicit ec: ExecutionContext) {

  def apply(refundId: UUID, userId: UUID): Future[DeleteRefund.Response] =
    for {
      _ <- refundsRepository.delete(refundId, userId)
    } yield DeleteRefund.Response()
}
