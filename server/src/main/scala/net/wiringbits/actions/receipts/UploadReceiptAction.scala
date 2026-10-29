package net.wiringbits.actions.receipts

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.receipts.UploadReceipt
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.{DocumentsRepository, ReceiptRepository}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Receipt}
import net.wiringbits.services.BlobStorage

import java.security.MessageDigest
import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class UploadReceiptAction @Inject() (
    documentsRepository: DocumentsRepository,
    receiptRepository: ReceiptRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext, clock: Clock) {

  def apply(userId: UUID, bytes: Array[Byte], kindStr: String, filename: String): Future[UploadReceipt.Response] = {
    val kind = DocumentKind.withNameOption(kindStr) match {
      case Some(k @ (DocumentKind.Receipt | DocumentKind.Invoice)) => k
      case _ => throw new RuntimeException(s"Unsupported receipt kind: $kindStr")
    }
    val sha256 = computeSha256(bytes)
    for {
      existingOpt <- documentsRepository.findBySha256(userId, sha256)
      receiptId <- existingOpt match {
        case Some(existing) =>
          receiptRepository.findByDocumentId(existing.id).map {
            case Some(r) => r.id
            case None => throw new RuntimeException("Receipt not found for existing document")
          }
        case None =>
          val now = clock.instant()
          val uploadDate = LocalDate.now(clock)
          val storageKey = s"data/$userId/$uploadDate/$sha256"
          val documentId = UuidCreator.getTimeOrderedEpoch()
          val receiptId = UuidCreator.getTimeOrderedEpoch()
          val document = Document.Create(
            id = documentId,
            userId = userId,
            sha256 = sha256,
            storageKey = storageKey,
            kind = kind,
            filename = filename,
            createdAt = now,
            updatedAt = now
          )
          val receipt = Receipt.Create(
            id = receiptId,
            userId = userId,
            documentId = documentId,
            manuallyEdited = false,
            status = DocumentStatus.Ingested,
            createdAt = now,
            updatedAt = now
          )
          val job = BackgroundJobData.Create(
            id = UUID.randomUUID(),
            `type` = BackgroundJobType.ProcessDocument,
            payload = BackgroundJobPayload.ProcessDocument(documentId),
            status = BackgroundJobStatus.Pending,
            executeAt = now,
            createdAt = now,
            updatedAt = now
          )
          for {
            _ <- blobStorage.put(storageKey, bytes)
            id <- receiptRepository.createWithDocument(document, receipt, job)
          } yield id
      }
    } yield UploadReceipt.Response(receiptId)
  }

  private def computeSha256(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString
  }
}
