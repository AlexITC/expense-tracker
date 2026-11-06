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

  private val AnalyzingText = "Receipt received, analyzing..."

  private val InFlightStatuses: Set[DocumentStatus] =
    Set(DocumentStatus.Ingested, DocumentStatus.Processing, DocumentStatus.Analyzed)

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
          sha256 = computeSha256(bytes)
          filename = originalFilename.getOrElse(s"telegram-$sha256$ext")
          existing <- documentsRepository.findBySha256(botUser.userId, sha256)
          _ <- existing match {
            case Some(document) =>
              linkExistingReceipt(userId = botUser.userId, documentId = document.id, chatId = chatId)
            case None =>
              storeNewReceipt(
                userId = botUser.userId,
                bytes = bytes,
                sha256 = sha256,
                filename = filename,
                chatId = chatId
              )
          }
        } yield ()
    }

  // A duplicate photo enqueues no job, so the link can be created after sending. Only a receipt still being processed
  // gets the analyzing text, since a pending job will update it; any other state gets a static acknowledgement.
  private def linkExistingReceipt(userId: UUID, documentId: UUID, chatId: Long): Future[Unit] =
    receiptRepository.findByDocumentId(documentId).flatMap {
      case None => Future.failed(new RuntimeException("Receipt not found for existing document"))
      case Some(receipt) =>
        telegramReceiptRepository.findByReceiptId(receipt.id).flatMap {
          case Some(_) =>
            logger.info(
              s"Telegram photo: duplicate photo, keeping existing message userId=$userId receiptId=${receipt.id}"
            )
            Future.unit
          case None =>
            val text =
              if (InFlightStatuses.contains(receipt.status)) AnalyzingText else "This receipt was already received."
            for {
              messageId <- sendInitialMessage(userId = userId, chatId = chatId, text = text)
              _ <- withOrphanCleanup(userId = userId, chatId = chatId, messageId = messageId) {
                telegramReceiptRepository.create(
                  id = UuidCreator.getTimeOrderedEpoch(),
                  receiptId = receipt.id,
                  chatId = chatId,
                  expenseMessageId = messageId
                )
              }
            } yield ()
        }
    }

  // The initial message is sent before the job is enqueued, and the job is stored in the same transaction as the
  // Telegram link holding the message id, so every later stage edits this single message.
  private def storeNewReceipt(
      userId: UUID,
      bytes: Array[Byte],
      sha256: String,
      filename: String,
      chatId: Long
  ): Future[Unit] = {
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
      messageId <- sendInitialMessage(userId = userId, chatId = chatId, text = AnalyzingText)
      _ <- withOrphanCleanup(userId = userId, chatId = chatId, messageId = messageId) {
        receiptRepository.createWithDocumentFromTelegram(
          document = document,
          receipt = receipt,
          job = job,
          telegramReceiptId = UuidCreator.getTimeOrderedEpoch(),
          chatId = chatId,
          messageId = messageId
        )
      }
      _ = logger.info(s"Telegram photo: receipt saved userId=$userId receiptId=$receiptId")
    } yield ()
  }

  // When sending fails, the link is stored without a message id and later stages send a fresh one.
  private def sendInitialMessage(userId: UUID, chatId: Long, text: String): Future[Option[Long]] =
    telegramApi
      .sendMessageReturningId(chatId = chatId, text = text, button = None)
      .map(Option(_))
      .recover { case e =>
        logger.warn(s"Telegram photo: failed to send initial message userId=$userId: ${e.getMessage}", e)
        None
      }

  // The message is sent before the row that references it is stored; when storing fails, nothing would ever edit the
  // message again, so it is turned into a failure text before the error propagates.
  private def withOrphanCleanup(userId: UUID, chatId: Long, messageId: Option[Long])(
      store: => Future[?]
  ): Future[Unit] =
    store.map(_ => ()).recoverWith { case e =>
      val cleanup = messageId.fold(Future.unit) { id =>
        logger.warn(s"Telegram photo: failed to store receipt, marking message $id as failed userId=$userId")
        telegramApi
          .editMessage(
            chatId = chatId,
            messageId = id,
            text = "Could not save the receipt. Please send it again.",
            button = None
          )
          .recover { case editError =>
            logger.warn(s"Telegram photo: failed to edit orphaned message $id userId=$userId: ${editError.getMessage}")
          }
      }
      cleanup.flatMap(_ => Future.failed(e))
    }

  private def computeSha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
}
