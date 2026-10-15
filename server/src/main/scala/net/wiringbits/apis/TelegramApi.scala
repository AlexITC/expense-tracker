package net.wiringbits.apis

import com.bot4s.telegram.clients.SttpClient
import com.bot4s.telegram.future.TelegramBot
import com.bot4s.telegram.methods.{EditMessageText, GetFile, SendMessage, SetWebhook}
import com.bot4s.telegram.models.{ChatId, InlineKeyboardButton, InlineKeyboardMarkup, Message, WebAppInfo}
import io.circe.Decoder
import net.wiringbits.config.TelegramConfig
import play.api.inject.ApplicationLifecycle
import sttp.client4.*
import sttp.client4.okhttp.OkHttpFutureBackend

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class TelegramApi @Inject() (telegramConfig: TelegramConfig, lifecycle: ApplicationLifecycle)(implicit
    ec: ExecutionContext
) extends TelegramBot {

  private implicit val backend: Backend[Future] = OkHttpFutureBackend()

  private given Decoder[Either[Boolean, Message]] =
    Decoder[Message].map(Right(_)).or(Decoder[Boolean].map(Left(_)))

  override val client = new SttpClient(telegramConfig.botToken)

  lifecycle.addStopHook(() => backend.close())

  def registerWebhook(webhookUrl: String): Future[Unit] =
    request(SetWebhook(url = webhookUrl, secretToken = Some(telegramConfig.webhookSecret))).map(_ => ())

  def sendMessage(chatId: Long, text: String): Future[Unit] =
    request(SendMessage(ChatId(chatId), text)).map(_ => ())

  def sendMessageWithWebAppButton(chatId: Long, text: String, webAppUrl: String, buttonLabel: String): Future[Long] = {
    val button = InlineKeyboardButton(text = buttonLabel, webApp = Some(WebAppInfo(webAppUrl)))
    request(SendMessage(ChatId(chatId), text, replyMarkup = Some(InlineKeyboardMarkup.singleButton(button))))
      .map(_.messageId.toLong)
  }

  def editMessage(chatId: Long, messageId: Long, text: String): Future[Unit] =
    request(EditMessageText(chatId = Some(ChatId(chatId)), messageId = Some(messageId.toInt), text = text))
      .map(_ => ())

  def downloadFile(fileId: String): Future[(Array[Byte], String)] =
    request(GetFile(fileId)).flatMap { file =>
      file.filePath match {
        case None => Future.failed(new RuntimeException(s"File $fileId has no path on Telegram servers"))
        case Some(path) =>
          val url = uri"https://api.telegram.org/file/bot${telegramConfig.botToken}/$path"
          val ext = path.lastIndexOf('.') match {
            case i if i >= 0 => path.substring(i).toLowerCase
            case _ => ".jpg"
          }
          basicRequest.get(url).response(asByteArrayAlways).send(backend).map(r => (r.body, ext))
      }
    }
}
