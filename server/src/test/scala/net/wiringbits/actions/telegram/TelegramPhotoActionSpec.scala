package net.wiringbits.actions.telegram

import net.wiringbits.apis.TelegramApi
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.services.NoOpBlobStorage
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import org.scalatestplus.mockito.MockitoSugar
import utils.{RandomData, RepositoryUtils}

import java.time.Clock
import java.util.UUID
import scala.concurrent.Future

class TelegramPhotoActionSpec extends RepositorySpec with RepositoryUtils with MockitoSugar {

  private val fakeBytes = Array[Byte](1, 2, 3, 4, 5)
  private val fakeChatId = 11111L

  private def newAction(
      telegramApi: TelegramApi
  )(using repositories: RepositoryComponents): TelegramPhotoAction =
    new TelegramPhotoAction(
      repositories.telegram,
      telegramApi,
      repositories.documents,
      repositories.receipts,
      repositories.telegramReceipt,
      new NoOpBlobStorage
    )(using executionContext, Clock.systemUTC())

  private def stubTelegramApi(): TelegramApi = {
    val api = mock[TelegramApi]
    when(api.downloadFile(anyString())).thenReturn(Future.successful((fakeBytes, ".jpg")))
    when(api.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)
    api
  }

  "apply" should {
    "create a telegram_receipt row after uploading the receipt" in withRepositories() { implicit repositories =>
      val telegramUserId = RandomData.randomTelegramUserId()
      val user = createUser().futureValue
      repositories.telegram.createBotUser(UUID.randomUUID(), user.id, telegramUserId).futureValue

      val api = stubTelegramApi()
      newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

      val receipts = repositories.receipts.all(user.id).futureValue
      receipts must have size 1
      val telegramReceipt = repositories.telegramReceipt.findByReceiptId(receipts.head.id).futureValue
      telegramReceipt must not be empty
      telegramReceipt.value.chatId must be(fakeChatId)
    }

    "send 'Receipt saved ✓' after uploading the receipt" in withRepositories() { implicit repositories =>
      val telegramUserId = RandomData.randomTelegramUserId()
      val user = createUser().futureValue
      repositories.telegram.createBotUser(UUID.randomUUID(), user.id, telegramUserId).futureValue

      val api = stubTelegramApi()
      newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

      verify(api).sendMessage(fakeChatId, "Receipt saved ✓")
    }

    "never create an expense" in withRepositories() { implicit repositories =>
      val telegramUserId = RandomData.randomTelegramUserId()
      val user = createUser().futureValue
      repositories.telegram.createBotUser(UUID.randomUUID(), user.id, telegramUserId).futureValue

      val api = stubTelegramApi()
      newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

      repositories.expenses.all(user.id, None, None, false).futureValue mustBe empty
    }

    "not create a duplicate telegram_receipt row when the same photo is sent twice" in withRepositories() {
      implicit repositories =>
        val telegramUserId = RandomData.randomTelegramUserId()
        val user = createUser().futureValue
        repositories.telegram.createBotUser(UUID.randomUUID(), user.id, telegramUserId).futureValue

        val api = stubTelegramApi()
        val action = newAction(api)
        action.apply("file-abc", telegramUserId, fakeChatId).futureValue
        action.apply("file-abc", telegramUserId, fakeChatId).futureValue

        val receipts = repositories.receipts.all(user.id).futureValue
        receipts must have size 1
        repositories.telegramReceipt.findByReceiptId(receipts.head.id).futureValue must not be empty
        verify(api, times(2)).sendMessage(fakeChatId, "Receipt saved ✓")
    }

    "send the not-linked message when the telegram user has no linked account" in withRepositories() {
      implicit repositories =>
        val telegramUserId = RandomData.randomTelegramUserId()
        val api = stubTelegramApi()
        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        verify(api).sendMessage(
          fakeChatId,
          "Account not linked. Generate a link token in the web app and send /link <token> here."
        )
        verify(api, never()).downloadFile(anyString())
    }
  }
}
