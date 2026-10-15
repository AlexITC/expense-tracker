package controllers

import net.wiringbits.actions.telegram.{TelegramHandleTextAction, TelegramLinkAction, TelegramPhotoAction}
import net.wiringbits.api.endpoints.TelegramEndpoints
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.TelegramConfig
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}
import sttp.capabilities.WebSockets
import sttp.capabilities.pekko.PekkoStreams
import sttp.tapir.server.ServerEndpoint

import java.security.MessageDigest
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class TelegramBotController @Inject() (
    telegramConfig: TelegramConfig,
    telegramApi: TelegramApi,
    telegramLinkAction: TelegramLinkAction,
    telegramHandleTextAction: TelegramHandleTextAction,
    telegramPhotoAction: TelegramPhotoAction
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def receiveUpdate(input: (String, String)): Future[Either[Unit, Unit]] = {
    val (secretToken, body) = input
    logger.info("Telegram webhook received")
    if (!constantTimeEquals(secretToken, telegramConfig.webhookSecret)) {
      logger.warn("Telegram webhook: rejected — invalid secret token")
      Future.successful(Right(()))
    } else {
      dispatch(Json.parse(body))
        .recover { case NonFatal(ex) =>
          logger.error(s"Telegram webhook error: ${ex.getMessage}", ex)
        }
        .map(_ => Right(()))
    }
  }

  private def dispatch(json: JsValue): Future[Unit] = {
    val msg = json \ "message"
    val telegramUserIdOpt = (msg \ "from" \ "id").asOpt[Long]
    val chatIdOpt = (msg \ "chat" \ "id").asOpt[Long]
    val textOpt = (msg \ "text").asOpt[String]
    val largestPhotoOpt = (msg \ "photo").asOpt[Seq[JsValue]].flatMap(_.lastOption)
    val documentOpt = (msg \ "document").asOpt[JsValue]
    val documentFileIdOpt = documentOpt.flatMap(d => (d \ "file_id").asOpt[String])
    val documentFilenameOpt = documentOpt.flatMap(d => (d \ "file_name").asOpt[String])

    (telegramUserIdOpt, chatIdOpt) match {
      case (Some(telegramUserId), Some(chatId)) =>
        (textOpt, largestPhotoOpt, documentFileIdOpt) match {
          case (Some("/help"), _, _) =>
            telegramApi.sendMessage(
              chatId,
              "Send one item per line:\n\namount\nmerchant\ncategory (optional)\n\nExample:\n10.50\nAmazon\nShopping\n\nSend a photo or PDF to save a receipt.\n\nCommands:\n/link <token> — link your account\n/help — this message"
            )
          case (Some(text), _, _) if text.startsWith("/link ") =>
            telegramLinkAction(text.stripPrefix("/link ").trim, telegramUserId, chatId)
          case (Some(text), _, _) =>
            telegramHandleTextAction(text, telegramUserId, chatId)
          case (_, Some(photo), _) =>
            val fileId = (photo \ "file_id").as[String]
            telegramPhotoAction(fileId, telegramUserId, chatId)
          case (_, _, Some(fileId)) =>
            telegramPhotoAction(fileId, telegramUserId, chatId, documentFilenameOpt)
          case _ =>
            logger.info(s"Telegram webhook: unhandled message type, ignoring")
            Future.unit
        }
      case _ =>
        Future.unit
    }
  }

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes("UTF-8"), b.getBytes("UTF-8"))

  def routes: List[ServerEndpoint[PekkoStreams & WebSockets, Future]] =
    List(TelegramEndpoints.receiveUpdate.serverLogic(receiveUpdate))
}
