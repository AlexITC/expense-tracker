package net.wiringbits.services

import com.bot4s.telegram.api.TelegramApiException
import net.wiringbits.apis.TelegramApi
import net.wiringbits.models.documents.DocumentClass
import net.wiringbits.repositories.models.TelegramReceipt
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

  /** Never fails: shows the text on the receipt's bot message when the document came from Telegram. */
  def updateReceiptMessage(
      documentId: UUID,
      userId: UUID,
      text: String,
      button: Option[TelegramApi.WebAppButton]
  ): Future[Unit] =
    receiptRepository
      .findByDocumentId(documentId)
      .flatMap {
        case None =>
          logger.info(s"userId=$userId, document $documentId has no receipt, not updating Telegram")
          Future.unit
        case Some(receipt) =>
          telegramReceiptRepository.findByReceiptId(receipt.id).flatMap {
            case None =>
              logger.info(s"userId=$userId, document $documentId has no Telegram link, not updating Telegram")
              Future.unit
            case Some(telegramReceipt) =>
              showOnReceiptMessage(telegramReceipt = telegramReceipt, userId = userId, text = text, button = button)
          }
      }
      .recover { case e =>
        logger.warn(s"userId=$userId, failed to update Telegram for document $documentId: ${e.getMessage}")
      }

  /** Never fails: edits the receipt's single bot message, or sends a fresh one and remembers it when editing is not
    * possible.
    */
  def showOnReceiptMessage(
      telegramReceipt: TelegramReceipt,
      userId: UUID,
      text: String,
      button: Option[TelegramApi.WebAppButton]
  ): Future[Unit] = {
    def sendAndStore(): Future[Unit] =
      for {
        newId <- telegramApi.sendMessageReturningId(chatId = telegramReceipt.chatId, text = text, button = button)
        _ <- telegramReceiptRepository.updateExpenseMessageId(id = telegramReceipt.telegramReceiptId, messageId = newId)
      } yield ()

    val result = telegramReceipt.expenseMessageId match {
      case Some(messageId) =>
        telegramApi
          .editMessage(chatId = telegramReceipt.chatId, messageId = messageId, text = text, button = button)
          .recoverWith {
            // Only a message that no longer exists is replaced; other failures keep the stored message so a
            // transient error does not leave a stale duplicate behind.
            case e: TelegramApiException if DocumentTelegramNotifier.isMessageGone(e) =>
              logger.info(
                s"userId=$userId, failed to edit Telegram message for receipt ${telegramReceipt.receiptId}, sending a new one: ${e.getMessage}"
              )
              sendAndStore()
          }
      case None => sendAndStore()
    }

    result.recover { case e =>
      logger.warn(
        s"userId=$userId, failed to show Telegram message for receipt ${telegramReceipt.receiptId}: ${e.getMessage}"
      )
    }
  }
}

object DocumentTelegramNotifier {

  private def isMessageGone(e: TelegramApiException): Boolean =
    Option(e.message).exists(m => m.contains("message to edit not found") || m.contains("message can't be edited"))

  def notATicketMessage(documentClass: DocumentClass): String = documentClass match {
    case DocumentClass.Other =>
      "This doesn't look like a purchase ticket. Please send the itemized ticket."
    case other =>
      s"This looks like ${other.withArticle}, not a purchase ticket. Please send the itemized ticket."
  }
}
