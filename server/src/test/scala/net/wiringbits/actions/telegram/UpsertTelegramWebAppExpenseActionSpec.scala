package net.wiringbits.actions.telegram

import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.WebAppConfig
import net.wiringbits.repositories.models.{Category, Expense, TelegramReceipt}
import net.wiringbits.repositories.{
  CategoriesRepository,
  ExpensesRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository
}
import net.wiringbits.services.DocumentTelegramNotifier
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import java.time.{Clock, Instant, LocalDate, LocalTime}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import utils.{FakeTelegramApi, RandomData}

class UpsertTelegramWebAppExpenseActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val webAppConfig = WebAppConfig("https://app.example.com")

  private def stubExpense(id: UUID, userId: UUID): Expense =
    Expense(
      id,
      userId,
      BigDecimal("10"),
      LocalDate.of(2026, 1, 1),
      Some(LocalTime.of(9, 0)),
      None,
      None,
      Instant.now(),
      Instant.now()
    )

  private def stubTelegramReceipt(expenseMessageId: Option[Long]): TelegramReceipt =
    TelegramReceipt(
      telegramReceiptId = UUID.randomUUID(),
      receiptId = UUID.randomUUID(),
      chatId = RandomData.randomTelegramUserId(),
      expenseMessageId = expenseMessageId,
      createdAt = Instant.now(),
      updatedAt = Instant.now()
    )

  private def editButton(expenseId: UUID): Option[TelegramApi.WebAppButton] =
    Some(TelegramApi.WebAppButton("Edit", s"https://app.example.com/api/telegram/webapp?expense=$expenseId"))

  private def stubNewExpense(
      userId: UUID,
      expenseId: UUID,
      merchantsRepo: MerchantsRepository,
      expensesRepo: ExpensesRepository
  ): Unit = {
    when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(None))
    when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(UUID.randomUUID()))
    when(expensesRepo.create(any(), any())).thenReturn(Future.unit)
  }

  private def stubExistingExpense(
      userId: UUID,
      expenseId: UUID,
      merchantsRepo: MerchantsRepository,
      expensesRepo: ExpensesRepository
  ): Unit = {
    when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(Some(stubExpense(expenseId, userId))))
    when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(UUID.randomUUID()))
    when(expensesRepo.findSplitsByExpenseId(expenseId, userId)).thenReturn(Future.successful(Nil))
    when(expensesRepo.update(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
  }

  private def newAction(
      merchantsRepo: MerchantsRepository = mock[MerchantsRepository],
      categoriesRepo: CategoriesRepository = mock[CategoriesRepository],
      expensesRepo: ExpensesRepository = mock[ExpensesRepository],
      receiptRepo: ReceiptRepository = mock[ReceiptRepository],
      telegramReceiptRepo: TelegramReceiptRepository = mock[TelegramReceiptRepository],
      telegramApi: TelegramApi = new FakeTelegramApi()
  ): UpsertTelegramWebAppExpenseAction =
    new UpsertTelegramWebAppExpenseAction(
      merchantsRepo,
      categoriesRepo,
      expensesRepo,
      receiptRepo,
      telegramReceiptRepo,
      new DocumentTelegramNotifier(receiptRepo, telegramReceiptRepo, telegramApi),
      webAppConfig
    )(using global, Clock.systemUTC())

  "apply" should {
    "return Left when merchant name is empty" in {
      val action = newAction()
      val result =
        action(
          UUID.randomUUID(),
          UUID.randomUUID(),
          None,
          BigDecimal("10"),
          LocalDate.now(),
          "",
          None,
          None
        ).futureValue
      result must be(Left(Error.Invalid("Merchant name must not be empty")))
    }

    "return Left when amount is zero" in {
      val action = newAction()
      val result =
        action(
          UUID.randomUUID(),
          UUID.randomUUID(),
          None,
          BigDecimal("0"),
          LocalDate.now(),
          "Cafe",
          None,
          None
        ).futureValue
      result must be(Left(Error.Invalid("Amount must be positive")))
    }

    "return Left when amount is negative" in {
      val action = newAction()
      val result =
        action(
          UUID.randomUUID(),
          UUID.randomUUID(),
          None,
          BigDecimal("-5"),
          LocalDate.now(),
          "Cafe",
          None,
          None
        ).futureValue
      result must be(Left(Error.Invalid("Amount must be positive")))
    }

    "return Left when merchant name exceeds 100 characters" in {
      val action = newAction()
      val result = action(
        UUID.randomUUID(),
        UUID.randomUUID(),
        None,
        BigDecimal("10"),
        LocalDate.now(),
        "x" * 101,
        None,
        None
      ).futureValue
      result must be(Left(Error.Invalid("Merchant name must be 100 characters or less")))
    }

    "return Left when category exceeds 25 characters" in {
      val action = newAction()
      val result = action(
        UUID.randomUUID(),
        UUID.randomUUID(),
        None,
        BigDecimal("10"),
        LocalDate.now(),
        "Cafe",
        Some("x" * 26),
        None
      ).futureValue
      result must be(Left(Error.Invalid("Category name must be 25 characters or less")))
    }

    "edit the stored photo-time message with the Edit button when creating the expense" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val storedMessageId = 77L
      val tr = stubTelegramReceipt(Some(storedMessageId))

      val merchantsRepo = mock[MerchantsRepository]
      val categoriesRepo = mock[CategoriesRepository]
      val expensesRepo = mock[ExpensesRepository]
      val receiptRepo = mock[ReceiptRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi()

      stubNewExpense(userId = userId, expenseId = expenseId, merchantsRepo = merchantsRepo, expensesRepo = expensesRepo)
      when(categoriesRepo.getOrCreate(userId, "Food"))
        .thenReturn(Future.successful(Category(UUID.randomUUID(), userId, "Food", Instant.now(), Instant.now())))
      when(receiptRepo.linkToExpense(receiptId, userId, expenseId)).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        categoriesRepo = categoriesRepo,
        expensesRepo = expensesRepo,
        receiptRepo = receiptRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(
          userId,
          expenseId,
          Some(receiptId),
          BigDecimal("12.50"),
          LocalDate.of(2026, 1, 15),
          "Coffee Shop",
          Some("Food"),
          None
        ).futureValue

      result must be(Right(()))
      verify(expensesRepo).create(any(), any())
      verify(receiptRepo).linkToExpense(receiptId, userId, expenseId)
      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = tr.chatId,
            messageId = storedMessageId,
            text = "Expense saved: Coffee Shop · 12.50 · 2026-01-15 · Food",
            button = editButton(expenseId)
          )
        )
      )
      telegramApi.sent must be(empty)
      verify(telegramReceiptRepo, never()).updateExpenseMessageId(any(), any())
    }

    "create expense without linking receipt when receiptId is absent" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val receiptRepo = mock[ReceiptRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi()

      stubNewExpense(userId = userId, expenseId = expenseId, merchantsRepo = merchantsRepo, expensesRepo = expensesRepo)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(None))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        receiptRepo = receiptRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(userId, expenseId, None, BigDecimal("5"), LocalDate.now(), "Shop", None, None).futureValue

      result must be(Right(()))
      verify(receiptRepo, never()).linkToExpense(any(), any(), any())
      telegramApi.sent must be(empty)
      telegramApi.edited must be(empty)
    }

    "edit the same stored message again when the expense is updated" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val storedMessageId = 55L
      val tr = stubTelegramReceipt(Some(storedMessageId))

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi()

      stubExistingExpense(
        userId = userId,
        expenseId = expenseId,
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo
      )
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(
          userId,
          expenseId,
          None,
          BigDecimal("20"),
          LocalDate.of(2026, 3, 5),
          "Restaurant",
          None,
          None
        ).futureValue

      result must be(Right(()))
      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = tr.chatId,
            messageId = storedMessageId,
            text = "Expense saved: Restaurant · 20 · 2026-03-05",
            button = editButton(expenseId)
          )
        )
      )
      telegramApi.sent must be(empty)
    }

    "send a fresh message with the Edit button and store its id when the stored message is gone" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val oldMessageId = 55L
      val tr = stubTelegramReceipt(Some(oldMessageId))

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi(editError = Some(FakeTelegramApi.messageGoneError))

      stubExistingExpense(
        userId = userId,
        expenseId = expenseId,
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo
      )
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))
      when(
        telegramReceiptRepo.updateExpenseMessageId(
          id = tr.telegramReceiptId,
          messageId = FakeTelegramApi.FirstMessageId
        )
      ).thenReturn(Future.unit)

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(
          userId,
          expenseId,
          None,
          BigDecimal("20"),
          LocalDate.of(2026, 3, 5),
          "Restaurant",
          None,
          None
        ).futureValue

      result must be(Right(()))
      telegramApi.edited.map(_.messageId) must be(List(oldMessageId))
      telegramApi.sent must be(
        List(
          FakeTelegramApi.Sent(
            chatId = tr.chatId,
            text = "Expense saved: Restaurant · 20 · 2026-03-05",
            button = editButton(expenseId)
          )
        )
      )
      verify(telegramReceiptRepo).updateExpenseMessageId(
        id = tr.telegramReceiptId,
        messageId = FakeTelegramApi.FirstMessageId
      )
    }

    "send a message with the Edit button and store its id when no message id is stored" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val tr = stubTelegramReceipt(None)

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi()

      stubNewExpense(userId = userId, expenseId = expenseId, merchantsRepo = merchantsRepo, expensesRepo = expensesRepo)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))
      when(
        telegramReceiptRepo.updateExpenseMessageId(
          id = tr.telegramReceiptId,
          messageId = FakeTelegramApi.FirstMessageId
        )
      ).thenReturn(Future.unit)

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(userId, expenseId, None, BigDecimal("8"), LocalDate.of(2026, 2, 1), "Bakery", None, None).futureValue

      result must be(Right(()))
      telegramApi.edited must be(empty)
      telegramApi.sent must be(
        List(
          FakeTelegramApi.Sent(
            chatId = tr.chatId,
            text = "Expense saved: Bakery · 8 · 2026-02-01",
            button = editButton(expenseId)
          )
        )
      )
      verify(telegramReceiptRepo).updateExpenseMessageId(
        id = tr.telegramReceiptId,
        messageId = FakeTelegramApi.FirstMessageId
      )
    }

    "return Right even when sending the Telegram message fails" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val tr = stubTelegramReceipt(None)

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi(failSends = true)

      stubNewExpense(userId = userId, expenseId = expenseId, merchantsRepo = merchantsRepo, expensesRepo = expensesRepo)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(userId, expenseId, None, BigDecimal("8"), LocalDate.now(), "Bakery", None, None).futureValue

      result must be(Right(()))
      telegramApi.sent.size must be(1)
      verify(telegramReceiptRepo, never()).updateExpenseMessageId(any(), any())
    }

    "return Right even when the Telegram receipt lookup fails" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = new FakeTelegramApi()

      stubExistingExpense(
        userId = userId,
        expenseId = expenseId,
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo
      )
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.failed(new RuntimeException("db error")))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        telegramReceiptRepo = telegramReceiptRepo,
        telegramApi = telegramApi
      )
      val result =
        action(userId, expenseId, None, BigDecimal("20"), LocalDate.now(), "Restaurant", None, None).futureValue

      result must be(Right(()))
      telegramApi.sent must be(empty)
      telegramApi.edited must be(empty)
    }
  }
}
