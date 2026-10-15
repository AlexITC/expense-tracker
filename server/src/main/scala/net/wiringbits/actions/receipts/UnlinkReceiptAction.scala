package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.UnlinkReceipt
import net.wiringbits.repositories.ReceiptRepository

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnlinkReceiptAction @Inject() (
    receiptRepository: ReceiptRepository
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(receiptId: UUID, userId: UUID): Future[UnlinkReceipt.Response] =
    for {
      receiptOpt <- receiptRepository.find(receiptId, userId)
      _ = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      _ <- receiptRepository.unlinkExpense(receiptId, userId)
    } yield UnlinkReceipt.Response()
}
