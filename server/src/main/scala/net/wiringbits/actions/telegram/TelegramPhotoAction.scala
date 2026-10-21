package net.wiringbits.actions.telegram

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.apis.TelegramApi
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Receipt}
import net.wiringbits.repositories.{
  DocumentsRepository,
  ReceiptRepository,
  TelegramReceiptRepository,
  TelegramRepository
}
import net.wiringbits.services.BlobStorage
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.time.{Clock, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TelegramPhotoAction @Inject() (
    telegramRepository: TelegramRepository,
    telegramApi: TelegramApi,
    documentsRepository: DocumentsRepository,
    receiptRepository: ReceiptRepository,
    telegramReceiptRepository: TelegramReceiptRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(
      fileId: String,
      telegramUserId: Long,
      chatId: Long,
      originalFilename: Option[String] = None
  ): Future[Unit] =
    telegramRepository.findBotUserByTelegramId(telegramUserId).flatMap {
      case None =>
        logger.info(s"Telegram photo: unlinked user telegramUserId=$telegramUserId")
        telegramApi.sendMessage(
          chatId,
          "Account not linked. Generate a link token in the web app and send /link <token> here."
        )
      case Some(botUser) =>
        for {
          (bytes, ext) <- telegramApi.downloadFile(fileId)
          filename = originalFilename.getOrElse(s"telegram-${computeSha256(bytes)}$ext")
          receiptId <- uploadReceipt(botUser.userId, bytes, filename)
          _ <- telegramReceiptRepository.findByReceiptId(receiptId).flatMap {
            case Some(_) => Future.unit
            case None => telegramReceiptRepository.create(UuidCreator.getTimeOrderedEpoch(), receiptId, chatId)
          }
          _ = logger.info(s"Telegram photo: receipt saved userId=${botUser.userId} receiptId=$receiptId")
          _ <- telegramApi.sendMessage(chatId, "Receipt saved ✓")
        } yield ()
    }

  private def uploadReceipt(userId: UUID, bytes: Array[Byte], filename: String): Future[UUID] = {
    val sha256 = computeSha256(bytes)
    documentsRepository.findBySha256(userId, sha256).flatMap {
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
          kind = DocumentKind.Receipt,
          filename = filename,
          status = DocumentStatus.Ingested,
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
  }

  private def computeSha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
