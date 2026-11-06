package net.wiringbits.actions.telegram

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.services.NoOpBlobStorage
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.Clock
import java.util.UUID
import scala.concurrent.Future

class TelegramPhotoActionSpec extends RepositorySpec with RepositoryUtils {

  private val fakeBytes = Array[Byte](1, 2, 3, 4, 5)
  private val fakeChatId = 11111L

  private def newAction(
      telegramApi: FakeTelegramApi
  )(using repositories: RepositoryComponents): TelegramPhotoAction =
    new TelegramPhotoAction(
      repositories.telegram,
      telegramApi,
      repositories.documents,
      repositories.receipts,
      repositories.telegramReceipt,
      new NoOpBlobStorage
    )(using executionContext, Clock.systemUTC())

  private def fakeTelegramApi(failSends: Boolean = false): FakeTelegramApi =
    new FakeTelegramApi(downloadResult = Future.successful((fakeBytes, ".jpg")), failSends = failSends)

  private def linkedTelegramUserId()(using repositories: RepositoryComponents): (UUID, Long) = {
    val telegramUserId = RandomData.randomTelegramUserId()
    val user = createUser().futureValue
    repositories.telegram.createBotUser(UUID.randomUUID(), user.id, telegramUserId).futureValue
    (user.id, telegramUserId)
  }

  "apply" should {
    "send one initial message without a button and store its id on the telegram_receipt" in withRepositories() {
      implicit repositories =>
        val (userId, telegramUserId) = linkedTelegramUserId()
        val api = fakeTelegramApi()

        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        api.sent must be(
          List(FakeTelegramApi.Sent(chatId = fakeChatId, text = "Receipt received, analyzing...", button = None))
        )
        api.edited mustBe empty
        val receipts = repositories.receipts.all(userId).futureValue
        receipts must have size 1
        val telegramReceipt = repositories.telegramReceipt.findByReceiptId(receipts.head.id).futureValue.value
        telegramReceipt.chatId must be(fakeChatId)
        telegramReceipt.expenseMessageId must be(Some(FakeTelegramApi.FirstMessageId))
    }

    "keep one row and one message when the same photo is sent twice" in withRepositories() { implicit repositories =>
      val (userId, telegramUserId) = linkedTelegramUserId()
      val api = fakeTelegramApi()
      val action = newAction(api)

      action.apply("file-abc", telegramUserId, fakeChatId).futureValue
      action.apply("file-abc", telegramUserId, fakeChatId).futureValue

      val receipts = repositories.receipts.all(userId).futureValue
      receipts must have size 1
      val telegramReceipt = repositories.telegramReceipt.findByReceiptId(receipts.head.id).futureValue.value
      telegramReceipt.expenseMessageId must be(Some(FakeTelegramApi.FirstMessageId))
      api.sent must have size 1
      api.edited mustBe empty
    }

    "never create an expense" in withRepositories() { implicit repositories =>
      val (userId, telegramUserId) = linkedTelegramUserId()
      val api = fakeTelegramApi()

      newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

      repositories.expenses.all(userId, None, None, false).futureValue mustBe empty
    }

    "complete and leave the message id empty when the initial send fails" in withRepositories() {
      implicit repositories =>
        val (userId, telegramUserId) = linkedTelegramUserId()
        val api = fakeTelegramApi(failSends = true)

        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        api.sent must have size 1
        val receipts = repositories.receipts.all(userId).futureValue
        receipts must have size 1
        val telegramReceipt = repositories.telegramReceipt.findByReceiptId(receipts.head.id).futureValue.value
        telegramReceipt.expenseMessageId mustBe empty
    }

    "send the not-linked message when the telegram user has no linked account" in withRepositories() {
      implicit repositories =>
        val telegramUserId = RandomData.randomTelegramUserId()
        // No download is configured, so any download attempt would fail the action.
        val api = new FakeTelegramApi()

        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        api.sent must be(
          List(
            FakeTelegramApi.Sent(
              chatId = fakeChatId,
              text = "Account not linked. Generate a link token in the web app and send /link <token> here.",
              button = None
            )
          )
        )
    }
  }
}
