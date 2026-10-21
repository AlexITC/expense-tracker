package net.wiringbits.services

import net.wiringbits.apis.TelegramApi
import net.wiringbits.models.documents.DocumentClass
import net.wiringbits.repositories.{ReceiptRepository, TelegramReceiptRepository}
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Tells the user about a document over Telegram, when the document came from a Telegram chat. */
class DocumentTelegramNotifier @Inject() (
    receiptRepository: ReceiptRepository,
    telegramReceiptRepository: TelegramReceiptRepository,
    telegramApi: TelegramApi
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  /** Never fails: a document without a Telegram link is not notified, and send failures are only logged. */
  def notify(documentId: UUID, userId: UUID, text: String): Future[Unit] =
    receiptRepository
      .findByDocumentId(documentId)
      .flatMap {
        case None =>
          logger.info(s"userId=$userId, document $documentId has no receipt, not notifying Telegram")
          Future.unit
        case Some(receipt) =>
          telegramReceiptRepository.findByReceiptId(receipt.id).flatMap {
            case None =>
              logger.info(s"userId=$userId, document $documentId has no Telegram link, not notifying Telegram")
              Future.unit
            case Some(telegramReceipt) => telegramApi.sendMessage(telegramReceipt.chatId, text)
          }
      }
      .recover { case e =>
        logger.warn(s"userId=$userId, failed to notify Telegram for document $documentId: ${e.getMessage}")
      }
}

object DocumentTelegramNotifier {

  def notATicketMessage(documentClass: DocumentClass): String = documentClass match {
    case DocumentClass.Other =>
      "This doesn't look like a purchase ticket — please send the itemized ticket."
    case other =>
      s"This looks like ${other.withArticle}, not a purchase ticket — please send the itemized ticket."
  }
}
