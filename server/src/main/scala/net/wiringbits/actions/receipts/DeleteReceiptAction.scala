package net.wiringbits.actions.receipts

import net.wiringbits.api.models.receipts.DeleteReceipt
import net.wiringbits.repositories.{DocumentsRepository, ReceiptRepository}
import net.wiringbits.services.BlobStorage

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class DeleteReceiptAction @Inject() (
    receiptRepository: ReceiptRepository,
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext) {

  def apply(receiptId: UUID, userId: UUID): Future[DeleteReceipt.Response] =
    for {
      receiptOpt <- receiptRepository.find(id = receiptId, userId = userId)
      receipt = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      _ = if (receipt.refundId.isDefined)
        throw new RuntimeException("Receipt is linked to a refund — unlink first")
      docOpt <- documentsRepository.find(id = receipt.documentId, userId = userId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      _ <- documentsRepository.delete(id = doc.id, userId = userId)
      _ <- blobStorage.delete(doc.storageKey)
    } yield DeleteReceipt.Response(receiptId)
}
