package net.wiringbits.actions.telegram

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.TelegramReceiptRepository
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Receipt}
import net.wiringbits.services.NoOpBlobStorage
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{Executors, FakeTelegramApi, RandomData, RepositoryUtils}

import java.security.MessageDigest
import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.Future

class TelegramPhotoActionSpec extends RepositorySpec with RepositoryUtils {

  private val fakeBytes = Array[Byte](1, 2, 3, 4, 5)
  private val fakeChatId = 11111L

  private def newAction(
      telegramApi: FakeTelegramApi,
      telegramReceiptRepository: Option[TelegramReceiptRepository] = None
  )(using repositories: RepositoryComponents): TelegramPhotoAction =
    new TelegramPhotoAction(
      repositories.telegram,
      telegramApi,
      repositories.documents,
      repositories.receipts,
      telegramReceiptRepository.getOrElse(repositories.telegramReceipt),
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

  // A receipt for the same photo bytes that did not come from Telegram, e.g. a web upload.
  private def createWebReceipt(userId: UUID, status: DocumentStatus)(using
      repositories: RepositoryComponents
  ): UUID = {
    val now = Instant.now()
    val sha256 = MessageDigest.getInstance("SHA-256").digest(fakeBytes).map(b => f"${b & 0xff}%02x").mkString
    val documentId = UUID.randomUUID()
    val receiptId = UUID.randomUUID()
    repositories.receipts
      .createWithDocument(
        Document.Create(
          id = documentId,
          userId = userId,
          sha256 = sha256,
          storageKey = "",
          kind = DocumentKind.Receipt,
          filename = "receipt.jpg",
          createdAt = now,
          updatedAt = now
        ),
        Receipt.Create(
          id = receiptId,
          userId = userId,
          documentId = documentId,
          manuallyEdited = false,
          status = DocumentStatus.Ingested,
          createdAt = now,
          updatedAt = now
        ),
        BackgroundJobData.Create(
          id = UUID.randomUUID(),
          `type` = BackgroundJobType.ProcessDocument,
          payload = BackgroundJobPayload.ProcessDocument(documentId),
          status = BackgroundJobStatus.Pending,
          executeAt = now,
          createdAt = now,
          updatedAt = now
        )
      )
      .futureValue
    repositories.receipts.updateStatus(receiptId, userId, status).futureValue
    receiptId
  }

  "apply" should {
    "acknowledge an already processed receipt from another source without the analyzing text" in withRepositories() {
      implicit repositories =>
        val (userId, telegramUserId) = linkedTelegramUserId()
        val receiptId = createWebReceipt(userId, DocumentStatus.Parsed)
        val api = fakeTelegramApi()

        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        api.sent must be(
          List(FakeTelegramApi.Sent(chatId = fakeChatId, text = "This receipt was already received.", button = None))
        )
        repositories.telegramReceipt.findByReceiptId(receiptId).futureValue.value.expenseMessageId must be(
          Some(FakeTelegramApi.FirstMessageId)
        )
    }

    "show the analyzing text for a receipt from another source that is still being processed" in withRepositories() {
      implicit repositories =>
        val (userId, telegramUserId) = linkedTelegramUserId()
        createWebReceipt(userId, DocumentStatus.Processing)
        val api = fakeTelegramApi()

        newAction(api).apply("file-abc", telegramUserId, fakeChatId).futureValue

        api.sent must be(
          List(FakeTelegramApi.Sent(chatId = fakeChatId, text = "Receipt received, analyzing...", button = None))
        )
    }

    "turn the sent message into a failure text when storing the link fails" in withRepositories() {
      implicit repositories =>
        val (userId, telegramUserId) = linkedTelegramUserId()
        createWebReceipt(userId, DocumentStatus.Parsed)
        val api = fakeTelegramApi()
        val failingStore = new TelegramReceiptRepository(repositories.database)(using Executors.databaseEC) {
          override def create(id: UUID, receiptId: UUID, chatId: Long, expenseMessageId: Option[Long]) =
            Future.failed(new RuntimeException("db down"))
        }

        newAction(api, telegramReceiptRepository = Some(failingStore))
          .apply("file-abc", telegramUserId, fakeChatId)
          .failed
          .futureValue
          .getMessage must be("db down")

        api.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = fakeChatId,
              messageId = FakeTelegramApi.FirstMessageId,
              text = "Could not save the receipt. Please send it again.",
              button = None
            )
          )
        )
    }

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
