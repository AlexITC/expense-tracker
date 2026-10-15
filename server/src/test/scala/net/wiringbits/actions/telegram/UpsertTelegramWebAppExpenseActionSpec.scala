package net.wiringbits.actions.telegram

import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.WebAppConfig
import net.wiringbits.repositories.models.{Expense, TelegramReceipt}
import net.wiringbits.repositories.{
  CategoriesRepository,
  ExpensesRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository
}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar

import java.time.{Clock, Instant, LocalDate, LocalTime}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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
    TelegramReceipt(UUID.randomUUID(), UUID.randomUUID(), 42L, expenseMessageId, Instant.now(), Instant.now())

  private def newAction(
      merchantsRepo: MerchantsRepository = mock[MerchantsRepository],
      categoriesRepo: CategoriesRepository = mock[CategoriesRepository],
      expensesRepo: ExpensesRepository = mock[ExpensesRepository],
      receiptRepo: ReceiptRepository = mock[ReceiptRepository],
      telegramReceiptRepo: TelegramReceiptRepository = mock[TelegramReceiptRepository],
      telegramApi: TelegramApi = mock[TelegramApi]
  ): UpsertTelegramWebAppExpenseAction =
    new UpsertTelegramWebAppExpenseAction(
      merchantsRepo,
      categoriesRepo,
      expensesRepo,
      receiptRepo,
      telegramReceiptRepo,
      telegramApi,
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

    "create expense, link receipt, send Telegram message when expense does not exist" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()
      val messageId = 99L
      val tr = stubTelegramReceipt(None)

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val receiptRepo = mock[ReceiptRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(None))
      when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(expensesRepo.create(any(), any())).thenReturn(Future.unit)
      when(receiptRepo.linkToExpense(receiptId, userId, expenseId)).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(messageId))
      when(telegramReceiptRepo.updateExpenseMessageId(tr.telegramReceiptId, messageId)).thenReturn(Future.unit)

      val action = newAction(
        merchantsRepo = merchantsRepo,
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
          None,
          None
        ).futureValue

      result must be(Right(()))
      verify(expensesRepo).create(any(), any())
      verify(receiptRepo).linkToExpense(receiptId, userId, expenseId)
      val textCaptor = ArgumentCaptor.forClass(classOf[String])
      verify(telegramApi).sendMessageWithWebAppButton(anyLong(), textCaptor.capture(), anyString(), anyString())
      textCaptor.getValue must be("Expense saved: Coffee Shop · 12.50 · 2026-01-15")
      verify(telegramReceiptRepo).updateExpenseMessageId(tr.telegramReceiptId, messageId)
    }

    "create expense without linking receipt when receiptId is absent" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val receiptRepo = mock[ReceiptRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]

      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(None))
      when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(expensesRepo.create(any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(None))

      val action = newAction(
        merchantsRepo = merchantsRepo,
        expensesRepo = expensesRepo,
        receiptRepo = receiptRepo,
        telegramReceiptRepo = telegramReceiptRepo
      )
      val result =
        action(userId, expenseId, None, BigDecimal("5"), LocalDate.now(), "Shop", None, None).futureValue

      result must be(Right(()))
      verify(receiptRepo, never()).linkToExpense(any(), any(), any())
    }

    "update expense and edit Telegram message when expense already exists" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()
      val messageId = 55L
      val tr = stubTelegramReceipt(Some(messageId))

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(Some(stubExpense(expenseId, userId))))
      when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(expensesRepo.findSplitsByExpenseId(expenseId, userId)).thenReturn(Future.successful(Nil))
      when(expensesRepo.update(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.editMessage(anyLong(), anyLong(), anyString())).thenReturn(Future.unit)

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
      val textCaptor = ArgumentCaptor.forClass(classOf[String])
      verify(telegramApi).editMessage(anyLong(), anyLong(), textCaptor.capture())
      textCaptor.getValue must be("Expense saved: Restaurant · 20 · 2026-03-05")
      verify(telegramApi, never()).sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString())
    }

    "fall back to sendMessageWithWebAppButton when editMessage fails" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()
      val oldMessageId = 55L
      val newMessageId = 99L
      val tr = stubTelegramReceipt(Some(oldMessageId))

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(Some(stubExpense(expenseId, userId))))
      when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(expensesRepo.findSplitsByExpenseId(expenseId, userId)).thenReturn(Future.successful(Nil))
      when(expensesRepo.update(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.editMessage(anyLong(), anyLong(), anyString()))
        .thenReturn(Future.failed(new RuntimeException("message too old")))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(newMessageId))
      when(telegramReceiptRepo.updateExpenseMessageId(tr.telegramReceiptId, newMessageId)).thenReturn(Future.unit)

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
      verify(telegramApi).editMessage(anyLong(), anyLong(), anyString())
      verify(telegramApi).sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString())
      verify(telegramReceiptRepo).updateExpenseMessageId(tr.telegramReceiptId, newMessageId)
    }

    "return Right even when Telegram notification fails entirely" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val merchantId = UUID.randomUUID()

      val merchantsRepo = mock[MerchantsRepository]
      val expensesRepo = mock[ExpensesRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]

      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(Some(stubExpense(expenseId, userId))))
      when(merchantsRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(expensesRepo.findSplitsByExpenseId(expenseId, userId)).thenReturn(Future.successful(Nil))
      when(expensesRepo.update(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByExpenseId(expenseId)).thenReturn(Future.failed(new RuntimeException("db error")))

      val action =
        newAction(merchantsRepo = merchantsRepo, expensesRepo = expensesRepo, telegramReceiptRepo = telegramReceiptRepo)
      val result =
        action(userId, expenseId, None, BigDecimal("20"), LocalDate.now(), "Restaurant", None, None).futureValue

      result must be(Right(()))
    }
  }
}
