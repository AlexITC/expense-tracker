package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.LinkReceiptToRefund
import net.wiringbits.repositories.{ReceiptRepository, RefundsRepository}

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class LinkReceiptToRefundAction @Inject() (
    receiptRepository: ReceiptRepository,
    refundsRepository: RefundsRepository
)(implicit ec: ExecutionContext) {

  def apply(
      receiptId: UUID,
      userId: UUID,
      request: LinkReceiptToRefund.Request
  ): Future[LinkReceiptToRefund.Response] =
    for {
      receiptOpt <- receiptRepository.find(id = receiptId, userId = userId)
      _ = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      refundOpt <- refundsRepository.find(id = request.refundId, userId = userId)
      _ = if (refundOpt.isEmpty) throw new RuntimeException("Refund not found")
      _ <- receiptRepository.linkToRefund(id = receiptId, userId = userId, refundId = request.refundId)
    } yield LinkReceiptToRefund.Response()
}
