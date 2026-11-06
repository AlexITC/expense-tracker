package net.wiringbits.actions.telegram

import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error
import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.WebAppConfig
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

import java.time.{Clock, LocalDate}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import utils.FakeTelegramApi

class UpsertTelegramWebAppExpenseActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val webAppConfig = WebAppConfig("https://app.example.com")

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
  }
}
