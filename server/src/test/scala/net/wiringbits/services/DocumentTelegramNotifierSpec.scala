package net.wiringbits.services

import net.wiringbits.apis.TelegramApi
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.models.TelegramReceipt
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{FakeTelegramApi, RandomData, RepositoryUtils}

import java.util.UUID

class DocumentTelegramNotifierSpec extends RepositorySpec with RepositoryUtils {

  private val button = TelegramApi.WebAppButton(label = "Create Expense", url = "https://app.example.com/webapp")

  private def newNotifier(telegramApi: FakeTelegramApi)(using
      repositories: RepositoryComponents
  ): DocumentTelegramNotifier =
    new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)(using
      executionContext
    )

  private def createTelegramReceipt(messageId: Option[Long])(using
      repositories: RepositoryComponents
  ): (UUID, TelegramReceipt) = {
    val user = createUser().futureValue
    val receipt = createReceipt(user.id).futureValue
    val telegramReceiptId = UUID.randomUUID()
    repositories.telegramReceipt
      .create(id = telegramReceiptId, receiptId = receipt.id, chatId = RandomData.randomTelegramUserId())
      .futureValue
    messageId.foreach { id =>
      repositories.telegramReceipt.updateExpenseMessageId(id = telegramReceiptId, messageId = id).futureValue
    }
    (user.id, repositories.telegramReceipt.findByReceiptId(receipt.id).futureValue.value)
  }

  "showOnReceiptMessage" should {
    "edit the stored message with the given button" in withRepositories() { implicit repositories =>
      val messageId = RandomData.randomTelegramUserId()
      val (userId, telegramReceipt) = createTelegramReceipt(Some(messageId))
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newNotifier(telegramApi)
        .showOnReceiptMessage(
          telegramReceipt = telegramReceipt,
          userId = userId,
          text = "Reading",
          button = Some(button)
        )
        .futureValue

      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = telegramReceipt.chatId,
            messageId = messageId,
            text = "Reading",
            button = Some(button)
          )
        )
      )
      telegramApi.sent must be(empty)
    }

    "send and store a fresh message when the message to edit is gone" in withRepositories() { implicit repositories =>
      val messageId = RandomData.randomTelegramUserId()
      val (userId, telegramReceipt) = createTelegramReceipt(Some(messageId))
      val editError = Some(FakeTelegramApi.messageGoneError)
      val telegramApi = new FakeTelegramApi(editError = editError)(using executionContext)

      newNotifier(telegramApi)
        .showOnReceiptMessage(
          telegramReceipt = telegramReceipt,
          userId = userId,
          text = "Reading",
          button = Some(button)
        )
        .futureValue

      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = telegramReceipt.chatId,
            messageId = messageId,
            text = "Reading",
            button = Some(button)
          )
        )
      )
      telegramApi.sent must be(
        List(FakeTelegramApi.Sent(chatId = telegramReceipt.chatId, text = "Reading", button = Some(button)))
      )
      val stored = repositories.telegramReceipt.findByReceiptId(telegramReceipt.receiptId).futureValue.value
      stored.expenseMessageId must be(Some(FakeTelegramApi.FirstMessageId))
    }

    "keep the stored message when the edit fails for another reason" in withRepositories() { implicit repositories =>
      val messageId = RandomData.randomTelegramUserId()
      val (userId, telegramReceipt) = createTelegramReceipt(Some(messageId))
      val telegramApi = new FakeTelegramApi(editError = Some(FakeTelegramApi.rateLimitedError))(using executionContext)

      newNotifier(telegramApi)
        .showOnReceiptMessage(telegramReceipt = telegramReceipt, userId = userId, text = "Reading", button = None)
        .futureValue

      telegramApi.edited.map(_.messageId) must be(List(messageId))
      telegramApi.sent must be(empty)
      val stored = repositories.telegramReceipt.findByReceiptId(telegramReceipt.receiptId).futureValue.value
      stored.expenseMessageId must be(Some(messageId))
    }

    "send a fresh message and store its id when no message is stored" in withRepositories() { implicit repositories =>
      val (userId, telegramReceipt) = createTelegramReceipt(None)
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newNotifier(telegramApi)
        .showOnReceiptMessage(telegramReceipt = telegramReceipt, userId = userId, text = "Reading", button = None)
        .futureValue

      telegramApi.edited must be(empty)
      telegramApi.sent must be(
        List(FakeTelegramApi.Sent(chatId = telegramReceipt.chatId, text = "Reading", button = None))
      )
      val stored = repositories.telegramReceipt.findByReceiptId(telegramReceipt.receiptId).futureValue.value
      stored.expenseMessageId must be(Some(FakeTelegramApi.FirstMessageId))
    }

    "succeed when Telegram fails entirely" in withRepositories() { implicit repositories =>
      val messageId = RandomData.randomTelegramUserId()
      val (userId, telegramReceipt) = createTelegramReceipt(Some(messageId))
      val telegramApi =
        new FakeTelegramApi(editError = Some(FakeTelegramApi.messageGoneError), failSends = true)(using
          executionContext
        )

      newNotifier(telegramApi)
        .showOnReceiptMessage(telegramReceipt = telegramReceipt, userId = userId, text = "Reading", button = None)
        .futureValue

      telegramApi.edited.map(_.messageId) must be(List(messageId))
      telegramApi.sent must be(
        List(FakeTelegramApi.Sent(chatId = telegramReceipt.chatId, text = "Reading", button = None))
      )

      val stored = repositories.telegramReceipt.findByReceiptId(telegramReceipt.receiptId).futureValue.value
      stored.expenseMessageId must be(Some(messageId))
    }
  }

  "updateReceiptMessage" should {
    "do nothing when the receipt has no Telegram link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newNotifier(telegramApi)
        .updateReceiptMessage(documentId = receipt.documentId, userId = user.id, text = "Reading", button = None)
        .futureValue

      telegramApi.sent must be(empty)
      telegramApi.edited must be(empty)
    }

    "edit the stored message of the document's receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val telegramReceiptId = UUID.randomUUID()
      val chatId = RandomData.randomTelegramUserId()
      val messageId = RandomData.randomTelegramUserId()
      repositories.telegramReceipt.create(id = telegramReceiptId, receiptId = receipt.id, chatId = chatId).futureValue
      repositories.telegramReceipt.updateExpenseMessageId(id = telegramReceiptId, messageId = messageId).futureValue
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newNotifier(telegramApi)
        .updateReceiptMessage(
          documentId = receipt.documentId,
          userId = user.id,
          text = "Reading",
          button = Some(button)
        )
        .futureValue

      telegramApi.edited must be(
        List(FakeTelegramApi.Edited(chatId = chatId, messageId = messageId, text = "Reading", button = Some(button)))
      )
      telegramApi.sent must be(empty)
    }
  }
}
