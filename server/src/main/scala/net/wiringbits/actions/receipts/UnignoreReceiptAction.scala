package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.UnignoreReceipt
import net.wiringbits.repositories.ReceiptRepository

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UnignoreReceiptAction @Inject() (
    receiptRepository: ReceiptRepository
)(implicit ec: ExecutionContext) {

  def apply(receiptId: UUID, userId: UUID): Future[UnignoreReceipt.Response] =
    receiptRepository.unignoreReceipt(receiptId, userId).map(_ => UnignoreReceipt.Response())
}
