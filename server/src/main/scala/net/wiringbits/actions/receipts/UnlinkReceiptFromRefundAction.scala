package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.UnlinkReceiptFromRefund
import net.wiringbits.repositories.ReceiptRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnlinkReceiptFromRefundAction @Inject() (receiptRepository: ReceiptRepository)(implicit ec: ExecutionContext) {

  def apply(receiptId: UUID, userId: UUID): Future[UnlinkReceiptFromRefund.Response] =
    for {
      _ <- receiptRepository.unlinkFromRefund(receiptId, userId)
    } yield UnlinkReceiptFromRefund.Response()
}
