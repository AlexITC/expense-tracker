package net.wiringbits.actions.telegram

import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.WebAppConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.repositories.TelegramReceiptRepository
import net.wiringbits.repositories.models.TelegramReceipt
import net.wiringbits.services.DocumentTelegramNotifier
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{Executors, FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.{Clock, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class UpsertTelegramWebAppExpenseActionTelegramSpec extends RepositorySpec with RepositoryUtils {

  private val webAppConfig = WebAppConfig("https://app.example.com")

  private def newAction(
      telegramApi: FakeTelegramApi,
      telegramReceiptRepository: Option[TelegramReceiptRepository] = None
  )(using repositories: RepositoryComponents): UpsertTelegramWebAppExpenseAction = {
    val telegramReceipts = telegramReceiptRepository.getOrElse(repositories.telegramReceipt)
    new UpsertTelegramWebAppExpenseAction(
      repositories.merchants,
      repositories.categories,
      repositories.expenses,
      repositories.receipts,
      telegramReceipts,
      new DocumentTelegramNotifier(repositories.receipts, telegramReceipts, telegramApi)(using executionContext),
      webAppConfig
    )(using executionContext, Clock.systemUTC())
  }

  private def editButton(expenseId: UUID): Option[TelegramApi.WebAppButton] =
    Some(TelegramApi.WebAppButton(label = "Edit", url = s"${webAppConfig.host}/api/telegram/webapp?expense=$expenseId"))

  // A receipt from a Telegram chat, optionally with its bot message id stored.
  private case class TelegramReceiptDoc(userId: UUID, receiptId: UUID, telegramReceiptId: UUID, chatId: Long)

  private def createTelegramReceipt(
      messageId: Option[Long]
  )(using repositories: RepositoryComponents): TelegramReceiptDoc = {
    val user = createUser().futureValue
    val receipt = createReceipt(user.id).futureValue
    val telegramReceiptId = UUID.randomUUID()
    val chatId = RandomData.randomTelegramUserId()
    repositories.telegramReceipt
      .create(id = telegramReceiptId, receiptId = receipt.id, chatId = chatId, expenseMessageId = messageId)
      .futureValue
    TelegramReceiptDoc(userId = user.id, receiptId = receipt.id, telegramReceiptId = telegramReceiptId, chatId = chatId)
  }

  private def storedMessageId(doc: TelegramReceiptDoc)(using repositories: RepositoryComponents): Option[Long] =
    repositories.telegramReceipt.findByReceiptId(doc.receiptId).futureValue.value.expenseMessageId

  private def upsert(
      action: UpsertTelegramWebAppExpenseAction,
      userId: UUID,
      expenseId: UUID,
      receiptId: Option[UUID],
      amount: BigDecimal,
      date: LocalDate,
      merchantName: String,
      category: Option[String] = None
  ) =
    action(
      userId = userId,
      expenseId = expenseId,
      receiptId = receiptId,
      amount = amount,
      date = date,
      merchantName = merchantName,
      category = category,
      notes = None
    ).futureValue

  "apply" should {
    "edit the stored photo-time message with the Edit button when creating the expense" in withRepositories() {
      implicit repositories =>
        val messageId = RandomData.randomTelegramUserId()
        val doc = createTelegramReceipt(Some(messageId))
        val expenseId = UUID.randomUUID()
        val telegramApi = new FakeTelegramApi()(using executionContext)

        val result = upsert(
          newAction(telegramApi),
          userId = doc.userId,
          expenseId = expenseId,
          receiptId = Some(doc.receiptId),
          amount = BigDecimal("12.50"),
          date = LocalDate.of(2026, 1, 15),
          merchantName = "Coffee Shop",
          category = Some("Food")
        )

        result must be(Right(()))
        repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.expenseId must be(Some(expenseId))
        telegramApi.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = doc.chatId,
              messageId = messageId,
              text = "Expense saved: Coffee Shop · 12.50 · 2026-01-15 · Food",
              button = editButton(expenseId)
            )
          )
        )
        telegramApi.sent must be(empty)
        storedMessageId(doc) must be(Some(messageId))
    }

    "edit the same stored message again when the expense is updated" in withRepositories() { implicit repositories =>
      val messageId = RandomData.randomTelegramUserId()
      val doc = createTelegramReceipt(Some(messageId))
      val expenseId = UUID.randomUUID()
      upsert(
        newAction(new FakeTelegramApi()(using executionContext)),
        userId = doc.userId,
        expenseId = expenseId,
        receiptId = Some(doc.receiptId),
        amount = BigDecimal("10"),
        date = LocalDate.of(2026, 3, 1),
        merchantName = "Restaurant"
      )
      val telegramApi = new FakeTelegramApi()(using executionContext)

      val result = upsert(
        newAction(telegramApi),
        userId = doc.userId,
        expenseId = expenseId,
        receiptId = None,
        amount = BigDecimal("20"),
        date = LocalDate.of(2026, 3, 5),
        merchantName = "Restaurant"
      )

      result must be(Right(()))
      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = doc.chatId,
            messageId = messageId,
            text = "Expense saved: Restaurant · 20 · 2026-03-05",
            button = editButton(expenseId)
          )
        )
      )
      telegramApi.sent must be(empty)
    }

    "send a fresh message with the Edit button and store its id when the stored message is gone" in withRepositories() {
      implicit repositories =>
        val oldMessageId = RandomData.randomTelegramUserId()
        val doc = createTelegramReceipt(Some(oldMessageId))
        val expenseId = UUID.randomUUID()
        val telegramApi =
          new FakeTelegramApi(editError = Some(FakeTelegramApi.messageGoneError))(using executionContext)

        val result = upsert(
          newAction(telegramApi),
          userId = doc.userId,
          expenseId = expenseId,
          receiptId = Some(doc.receiptId),
          amount = BigDecimal("20"),
          date = LocalDate.of(2026, 3, 5),
          merchantName = "Restaurant"
        )

        result must be(Right(()))
        telegramApi.edited.map(_.messageId) must be(List(oldMessageId))
        telegramApi.sent must be(
          List(
            FakeTelegramApi.Sent(
              chatId = doc.chatId,
              text = "Expense saved: Restaurant · 20 · 2026-03-05",
              button = editButton(expenseId)
            )
          )
        )
        storedMessageId(doc) must be(Some(FakeTelegramApi.FirstMessageId))
    }

    "send a message with the Edit button and store its id when no message id is stored" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt(None)
        val expenseId = UUID.randomUUID()
        val telegramApi = new FakeTelegramApi()(using executionContext)

        val result = upsert(
          newAction(telegramApi),
          userId = doc.userId,
          expenseId = expenseId,
          receiptId = Some(doc.receiptId),
          amount = BigDecimal("8"),
          date = LocalDate.of(2026, 2, 1),
          merchantName = "Bakery"
        )

        result must be(Right(()))
        telegramApi.edited must be(empty)
        telegramApi.sent must be(
          List(
            FakeTelegramApi.Sent(
              chatId = doc.chatId,
              text = "Expense saved: Bakery · 8 · 2026-02-01",
              button = editButton(expenseId)
            )
          )
        )
        storedMessageId(doc) must be(Some(FakeTelegramApi.FirstMessageId))
    }

    "return Right even when sending the Telegram message fails" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt(None)
      val expenseId = UUID.randomUUID()
      val telegramApi = new FakeTelegramApi(failSends = true)(using executionContext)

      val result = upsert(
        newAction(telegramApi),
        userId = doc.userId,
        expenseId = expenseId,
        receiptId = Some(doc.receiptId),
        amount = BigDecimal("8"),
        date = LocalDate.of(2026, 2, 1),
        merchantName = "Bakery"
      )

      result must be(Right(()))
      repositories.expenses.find(expenseId, doc.userId).futureValue must not be empty
      telegramApi.sent must have size 1
      storedMessageId(doc) mustBe empty
    }

    "return Right even when the Telegram receipt lookup fails" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt(Some(RandomData.randomTelegramUserId()))
      val expenseId = UUID.randomUUID()
      val telegramApi = new FakeTelegramApi()(using executionContext)
      val failingLookup = new TelegramReceiptRepository(repositories.database)(using Executors.databaseEC) {
        override def findByExpenseId(expenseId: UUID): Future[Option[TelegramReceipt]] =
          Future.failed(new RuntimeException("db error"))
      }

      val result = upsert(
        newAction(telegramApi, telegramReceiptRepository = Some(failingLookup)),
        userId = doc.userId,
        expenseId = expenseId,
        receiptId = Some(doc.receiptId),
        amount = BigDecimal("20"),
        date = LocalDate.of(2026, 3, 5),
        merchantName = "Restaurant"
      )

      result must be(Right(()))
      repositories.expenses.find(expenseId, doc.userId).futureValue must not be empty
      telegramApi.sent must be(empty)
      telegramApi.edited must be(empty)
    }
  }
}
