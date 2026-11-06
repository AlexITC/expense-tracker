package utils

import com.bot4s.telegram.api.TelegramApiException
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.TelegramConfig
import play.api.inject.DefaultApplicationLifecycle

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

// Records every outgoing Telegram call without touching the network.
class FakeTelegramApi(
    downloadResult: Future[(Array[Byte], String)] =
      Future.failed(new RuntimeException("FakeTelegramApi: no download configured")),
    editError: Option[Throwable] = None,
    failSends: Boolean = false
)(implicit ec: ExecutionContext)
    extends TelegramApi(
      TelegramConfig(enabled = false, botToken = "test-token", webhookSecret = "test-secret", botName = "test"),
      new DefaultApplicationLifecycle
    ) {
  import FakeTelegramApi.*

  private val nextMessageId = new AtomicLong(FirstMessageId - 1)
  private val sentQueue = new ConcurrentLinkedQueue[Sent]()
  private val editedQueue = new ConcurrentLinkedQueue[Edited]()

  def sent: Seq[Sent] = sentQueue.asScala.toList
  def edited: Seq[Edited] = editedQueue.asScala.toList

  override def sendMessage(chatId: Long, text: String): Future[Unit] =
    sendMessageReturningId(chatId = chatId, text = text, button = None).map(_ => ())

  override def sendMessageReturningId(
      chatId: Long,
      text: String,
      button: Option[TelegramApi.WebAppButton]
  ): Future[Long] = {
    sentQueue.add(Sent(chatId = chatId, text = text, button = button))
    if (failSends) Future.failed(new RuntimeException("FakeTelegramApi: send failed"))
    else Future.successful(nextMessageId.incrementAndGet())
  }

  override def editMessage(
      chatId: Long,
      messageId: Long,
      text: String,
      button: Option[TelegramApi.WebAppButton]
  ): Future[Unit] = {
    editedQueue.add(Edited(chatId = chatId, messageId = messageId, text = text, button = button))
    editError.map(Future.failed).getOrElse(Future.unit)
  }

  override def downloadFile(fileId: String): Future[(Array[Byte], String)] = downloadResult
}

object FakeTelegramApi {
  val FirstMessageId: Long = 1001L

  def messageGoneError: TelegramApiException =
    TelegramApiException("Bad Request: message to edit not found", 400, None, None)

  def rateLimitedError: TelegramApiException =
    TelegramApiException("Too Many Requests: retry after 1", 429, None, None)

  final case class Sent(chatId: Long, text: String, button: Option[TelegramApi.WebAppButton])
  final case class Edited(chatId: Long, messageId: Long, text: String, button: Option[TelegramApi.WebAppButton])
}
