package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.IgnoreReceipt
import net.wiringbits.repositories.ReceiptRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class IgnoreReceiptAction @Inject() (
    receiptRepository: ReceiptRepository
)(implicit ec: ExecutionContext) {

  def apply(receiptId: UUID, userId: UUID): Future[IgnoreReceipt.Response] =
    receiptRepository.ignoreReceipt(receiptId, userId).map(_ => IgnoreReceipt.Response())
}
