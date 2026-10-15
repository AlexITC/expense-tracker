package net.wiringbits.apis

import cats.instances.future.catsStdInstancesForFuture
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.methods.{EditMessageText, Request, SendMessage}
import com.bot4s.telegram.models.{Chat, ChatType, InlineKeyboardMarkup}
import io.circe.{Decoder, Encoder}
import net.wiringbits.config.TelegramConfig
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.ApplicationLifecycle

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TelegramApiSpec extends AnyWordSpec with MockitoSugar {

  private val fakeConfig =
    TelegramConfig(enabled = true, botToken = "test-token", webhookSecret = "test-secret", botName = "test")
  private val fakeLifecycle = mock[ApplicationLifecycle]

  // Captures the last request sent and returns a preset response.
  private class FakeRequestHandler(response: Any) extends RequestHandler[Future] {
    @volatile var lastRequest: Any = null

    override def sendRequest[T <: Request](
        request: T
    )(using enc: Encoder[T], dec: Decoder[request.Response]): Future[request.Response] = {
      lastRequest = request
      Future.successful(response.asInstanceOf[request.Response])
    }
  }

  private def makeApi(handler: FakeRequestHandler): TelegramApi =
    new TelegramApi(fakeConfig, fakeLifecycle) {
      override val client: RequestHandler[Future] = handler
    }

  private val fakeChat = Chat(id = 12345L, `type` = ChatType.Private)

  "sendMessageWithWebAppButton" should {
    "return the message_id from the response" in {
      import com.bot4s.telegram.models.Message
      val fakeMessage = Message(messageId = 42, date = 0, chat = fakeChat)
      val handler = new FakeRequestHandler(fakeMessage)

      val result =
        makeApi(handler).sendMessageWithWebAppButton(12345L, "Hello", "https://app.example.com", "Open").futureValue
      result must be(42L)
    }

    "send a SendMessage with an InlineKeyboardMarkup WebApp button" in {
      import com.bot4s.telegram.models.{ChatId, Message}
      val fakeMessage = Message(messageId = 7, date = 0, chat = fakeChat)
      val handler = new FakeRequestHandler(fakeMessage)

      makeApi(handler).sendMessageWithWebAppButton(99L, "Hi", "https://web.example.com", "Launch App").futureValue

      val sent = handler.lastRequest.asInstanceOf[SendMessage]
      sent.chatId must be(ChatId(99L))
      sent.text must be("Hi")
      val markup = sent.replyMarkup.value.asInstanceOf[InlineKeyboardMarkup]
      val button = markup.inlineKeyboard.head.head
      button.text must be("Launch App")
      button.webApp.value.url must be("https://web.example.com")
    }
  }

  "editMessage" should {
    "send an EditMessageText request with the correct parameters" in {
      import com.bot4s.telegram.models.{ChatId, Message}
      // EditMessageText's Response is Either[Boolean, Message]: Right(Message) when the edited
      // message was sent by the bot, Left(true) otherwise (e.g. inline messages) — see TelegramApi's
      // custom Decoder[Either[Boolean, Message]].
      val fakeMessage = Message(messageId = 1, date = 0, chat = fakeChat)
      val handler = new FakeRequestHandler(Right(fakeMessage): Either[Boolean, Message])

      makeApi(handler).editMessage(12345L, 99L, "Updated text").futureValue

      val sent = handler.lastRequest.asInstanceOf[EditMessageText]
      sent.chatId.value must be(ChatId(12345L))
      sent.messageId.value must be(99)
      sent.text must be("Updated text")
    }
  }
}
