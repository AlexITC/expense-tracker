package net.wiringbits.apis

import com.bot4s.telegram.api.TelegramApiException
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

object TelegramApi {
  final case class WebAppButton(label: String, url: String)
}

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

  def sendMessageReturningId(chatId: Long, text: String, button: Option[TelegramApi.WebAppButton]): Future[Long] =
    request(SendMessage(ChatId(chatId), text, replyMarkup = button.map(webAppMarkup)))
      .map(_.messageId.toLong)

  // bot4s omits a None replyMarkup from the JSON, so an empty keyboard is sent to make Telegram drop any existing
  // button. Re-sending identical content is treated as success because the message already shows what we want.
  def editMessage(chatId: Long, messageId: Long, text: String, button: Option[TelegramApi.WebAppButton]): Future[Unit] =
    request(
      EditMessageText(
        chatId = Some(ChatId(chatId)),
        messageId = Some(messageId.toInt),
        text = text,
        replyMarkup = Some(button.map(webAppMarkup).getOrElse(InlineKeyboardMarkup(Seq.empty)))
      )
    ).map(_ => ())
      .recover {
        case e: TelegramApiException if Option(e.message).exists(_.contains("message is not modified")) => ()
      }

  // sttp's uri"" interpolator builds its arguments through Any; it is the idiomatic way to build an
  // encoded Uri, so suppress the Any wart for this method.
  @SuppressWarnings(Array("org.wartremover.warts.Any"))
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

  private def webAppMarkup(button: TelegramApi.WebAppButton): InlineKeyboardMarkup =
    InlineKeyboardMarkup.singleButton(InlineKeyboardButton(text = button.label, webApp = Some(WebAppInfo(button.url))))
}
