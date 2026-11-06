package net.wiringbits.apis

import cats.instances.future.catsStdInstancesForFuture
import com.bot4s.telegram.api.{RequestHandler, TelegramApiException}
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

  // Fails every request with the given error.
  private class FailingRequestHandler(error: Throwable) extends RequestHandler[Future] {
    override def sendRequest[T <: Request](
        request: T
    )(using enc: Encoder[T], dec: Decoder[request.Response]): Future[request.Response] =
      Future.failed(error)
  }

  private def makeApi(handler: RequestHandler[Future]): TelegramApi =
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

  "sendMessageReturningId" should {
    "return the message_id from the response" in {
      import com.bot4s.telegram.models.Message
      val handler = new FakeRequestHandler(Message(messageId = 42, date = 0, chat = fakeChat))

      val result = makeApi(handler).sendMessageReturningId(chatId = 12345L, text = "Hello", button = None).futureValue
      result must be(42L)
    }

    "attach the web-app button markup" in {
      import com.bot4s.telegram.models.{ChatId, Message}
      val handler = new FakeRequestHandler(Message(messageId = 7, date = 0, chat = fakeChat))

      makeApi(handler)
        .sendMessageReturningId(
          chatId = 99L,
          text = "Hi",
          button = Some(TelegramApi.WebAppButton(label = "Launch App", url = "https://web.example.com"))
        )
        .futureValue

      val sent = handler.lastRequest.asInstanceOf[SendMessage]
      sent.chatId must be(ChatId(99L))
      sent.text must be("Hi")
      val button = sent.replyMarkup.value.asInstanceOf[InlineKeyboardMarkup].inlineKeyboard.head.head
      button.text must be("Launch App")
      button.webApp.value.url must be("https://web.example.com")
    }

    "send no markup when there is no button" in {
      import com.bot4s.telegram.models.Message
      val handler = new FakeRequestHandler(Message(messageId = 7, date = 0, chat = fakeChat))

      makeApi(handler).sendMessageReturningId(chatId = 99L, text = "Hi", button = None).futureValue

      handler.lastRequest.asInstanceOf[SendMessage].replyMarkup must be(None)
    }
  }

  "editMessage" should {
    "set the web-app markup when a button is given" in {
      import com.bot4s.telegram.models.{ChatId, Message}
      val handler =
        new FakeRequestHandler(Right(Message(messageId = 1, date = 0, chat = fakeChat)): Either[Boolean, Message])

      makeApi(handler)
        .editMessage(
          chatId = 12345L,
          messageId = 99L,
          text = "Updated text",
          button = Some(TelegramApi.WebAppButton(label = "Edit", url = "https://web.example.com/edit"))
        )
        .futureValue

      val sent = handler.lastRequest.asInstanceOf[EditMessageText]
      sent.chatId.value must be(ChatId(12345L))
      sent.messageId.value must be(99)
      sent.text must be("Updated text")
      val button = sent.replyMarkup.value.asInstanceOf[InlineKeyboardMarkup].inlineKeyboard.head.head
      button.text must be("Edit")
      button.webApp.value.url must be("https://web.example.com/edit")
    }

    "send an empty keyboard to clear the button when there is no button" in {
      import com.bot4s.telegram.models.Message
      val handler =
        new FakeRequestHandler(Right(Message(messageId = 1, date = 0, chat = fakeChat)): Either[Boolean, Message])

      makeApi(handler).editMessage(chatId = 12345L, messageId = 99L, text = "Updated text", button = None).futureValue

      val sent = handler.lastRequest.asInstanceOf[EditMessageText]
      sent.replyMarkup.value.asInstanceOf[InlineKeyboardMarkup].inlineKeyboard must be(empty)
    }

    "succeed when Telegram reports the message is not modified" in {
      val handler = new FailingRequestHandler(
        TelegramApiException(
          "Bad Request: message is not modified: specified new message content and reply markup are exactly the same",
          400
        )
      )

      makeApi(handler).editMessage(chatId = 12345L, messageId = 99L, text = "Same", button = None).futureValue must be(
        ()
      )
    }

    "fail on any other Telegram error" in {
      val handler = new FailingRequestHandler(TelegramApiException("Bad Request: message to edit not found", 400))

      val result = makeApi(handler).editMessage(chatId = 12345L, messageId = 99L, text = "Gone", button = None)
      result.failed.futureValue mustBe a[TelegramApiException]
    }
  }
}
