package net.wiringbits.actions.receipts

import net.wiringbits.repositories.{DocumentsRepository, ReceiptRepository}
import net.wiringbits.services.BlobStorage
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class GetReceiptFileAction @Inject() (
    receiptRepository: ReceiptRepository,
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(receiptId: UUID, userId: UUID): Future[(Array[Byte], String)] = {
    logger.info(s"Get receipt file: receiptId=$receiptId userId=$userId")
    for {
      receiptOpt <- receiptRepository.find(receiptId, userId)
      receipt = receiptOpt.getOrElse(throw new RuntimeException("Receipt not found"))
      docOpt <- documentsRepository.find(receipt.documentId, userId)
      doc = docOpt.getOrElse(throw new RuntimeException("Document not found"))
      bytesOpt <- blobStorage.get(doc.storageKey)
      bytes = bytesOpt.getOrElse(throw new RuntimeException("File not found in storage"))
    } yield (bytes, doc.filename)
  }
}
