package controllers

import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction
import net.wiringbits.actions.telegram.UpsertTelegramWebAppExpenseAction.Error as UpsertExpenseError
import net.wiringbits.config.TelegramConfig
import net.wiringbits.repositories.models.{BotUser, Expense, Receipt}
import net.wiringbits.repositories.{
  CategoriesRepository,
  ExpensesRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramRepository
}
import net.wiringbits.models.documents.DocumentStatus
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Helpers}

import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class TelegramWebAppControllerSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val devConfig =
    TelegramConfig(enabled = true, botToken = "test", webhookSecret = "sec", botName = "bot", webAppDevMode = true)
  private val prodConfig =
    TelegramConfig(enabled = true, botToken = "test", webhookSecret = "sec", botName = "bot", webAppDevMode = false)

  private def makeController(
      config: TelegramConfig,
      telegramRepo: TelegramRepository = mock[TelegramRepository],
      receiptRepo: ReceiptRepository = mock[ReceiptRepository],
      expensesRepo: ExpensesRepository = mock[ExpensesRepository],
      merchantsRepo: MerchantsRepository = mock[MerchantsRepository],
      categoriesRepo: CategoriesRepository = mock[CategoriesRepository],
      upsertExpenseAction: UpsertTelegramWebAppExpenseAction = mock[UpsertTelegramWebAppExpenseAction]
  ): TelegramWebAppController =
    new TelegramWebAppController(
      Helpers.stubControllerComponents(),
      config,
      telegramRepo,
      receiptRepo,
      expensesRepo,
      merchantsRepo,
      categoriesRepo,
      upsertExpenseAction
    )

  /** Builds a correctly-signed Telegram initData string for the given botToken and telegramUserId. */
  private def signedInitData(botToken: String, telegramUserId: Long): String = {
    val userJson = s"""{"id":$telegramUserId}"""
    val dataCheckString = s"user=$userJson"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec("WebAppData".getBytes("UTF-8"), "HmacSHA256"))
    val secretKey = mac.doFinal(botToken.getBytes("UTF-8"))
    mac.init(new SecretKeySpec(secretKey, "HmacSHA256"))
    val hash = mac.doFinal(dataCheckString.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    s"user=${java.net.URLEncoder.encode(userJson, "UTF-8")}&hash=$hash"
  }

  private def stubReceipt(id: UUID, userId: UUID): Receipt =
    Receipt(
      id,
      userId,
      UUID.randomUUID(),
      amount = Some(BigDecimal("12.50")),
      date = Some(LocalDate.of(2026, 1, 15)),
      time = None,
      merchantName = Some("Coffee Shop"),
      taxId = None,
      merchantId = None,
      expenseId = None,
      refundId = None,
      ignoredAt = None,
      manuallyEdited = false,
      parserVersion = None,
      status = DocumentStatus.Parsed,
      createdAt = Instant.now(),
      updatedAt = Instant.now(),
      documentFilename = "receipt.jpg"
    )

  private def stubExpense(id: UUID, userId: UUID): Expense =
    Expense(
      id,
      userId,
      amount = BigDecimal("99.00"),
      date = LocalDate.of(2026, 3, 1),
      time = None,
      merchantId = None,
      notes = Some("Dinner"),
      createdAt = Instant.now(),
      updatedAt = Instant.now()
    )

  "dev-mode" should {
    "return Unauthorized when dev_user_id is missing" in {
      val controller = makeController(devConfig)
      val request = FakeRequest(GET, "/telegram/webapp?receipt=abc")
      val result = controller.index(receipt = Some("abc"), expense = None, dev_user_id = None)(request)
      status(result) must be(UNAUTHORIZED)
    }

    "return Unauthorized when dev_user_id is not a valid UUID" in {
      val controller = makeController(devConfig)
      val request = FakeRequest(GET, "/telegram/webapp?receipt=abc&dev_user_id=notauuid")
      val result = controller.index(receipt = Some("abc"), expense = None, dev_user_id = Some("notauuid"))(request)
      status(result) must be(UNAUTHORIZED)
    }

    "return BadRequest when neither receipt nor expense is provided" in {
      val userId = UUID.randomUUID()
      val controller = makeController(devConfig)
      val request = FakeRequest(GET, s"/telegram/webapp?dev_user_id=$userId")
      val result = controller.index(receipt = None, expense = None, dev_user_id = Some(userId.toString))(request)
      status(result) must be(BAD_REQUEST)
    }

    "return BadRequest when receipt ID is not a valid UUID" in {
      val userId = UUID.randomUUID()
      val controller = makeController(devConfig)
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=notauuid&dev_user_id=$userId")
      val result =
        controller.index(receipt = Some("notauuid"), expense = None, dev_user_id = Some(userId.toString))(request)
      status(result) must be(BAD_REQUEST)
    }

    "return NotFound when receipt does not exist" in {
      val userId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val receiptRepo = mock[ReceiptRepository]
      when(receiptRepo.find(receiptId, userId)).thenReturn(Future.successful(None))
      val controller = makeController(devConfig, receiptRepo = receiptRepo)
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=$receiptId&dev_user_id=$userId")
      val result =
        controller.index(receipt = Some(receiptId.toString), expense = None, dev_user_id = Some(userId.toString))(
          request
        )
      status(result) must be(NOT_FOUND)
    }

    "return OK with prefilled form for a receipt" in {
      val userId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val receiptRepo = mock[ReceiptRepository]
      val merchantsRepo = mock[MerchantsRepository]
      val categoriesRepo = mock[CategoriesRepository]
      when(receiptRepo.find(receiptId, userId)).thenReturn(Future.successful(Some(stubReceipt(receiptId, userId))))
      when(merchantsRepo.all(userId)).thenReturn(Future.successful(Nil))
      when(categoriesRepo.all(userId)).thenReturn(Future.successful(Nil))
      val controller = makeController(
        devConfig,
        receiptRepo = receiptRepo,
        merchantsRepo = merchantsRepo,
        categoriesRepo = categoriesRepo
      )
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=$receiptId&dev_user_id=$userId")
      val result =
        controller.index(receipt = Some(receiptId.toString), expense = None, dev_user_id = Some(userId.toString))(
          request
        )
      status(result) must be(OK)
      val body = contentAsString(result)
      body must include("12.50")
      body must include("2026-01-15")
      body must include("Coffee Shop")
    }

    "return OK with prefilled form for an expense" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val expensesRepo = mock[ExpensesRepository]
      val merchantsRepo = mock[MerchantsRepository]
      val categoriesRepo = mock[CategoriesRepository]
      when(expensesRepo.find(expenseId, userId)).thenReturn(Future.successful(Some(stubExpense(expenseId, userId))))
      when(expensesRepo.findSplitsByExpenseId(expenseId, userId)).thenReturn(Future.successful(Nil))
      when(merchantsRepo.all(userId)).thenReturn(Future.successful(Nil))
      when(categoriesRepo.all(userId)).thenReturn(Future.successful(Nil))
      val controller = makeController(
        devConfig,
        expensesRepo = expensesRepo,
        merchantsRepo = merchantsRepo,
        categoriesRepo = categoriesRepo
      )
      val request = FakeRequest(GET, s"/telegram/webapp?expense=$expenseId&dev_user_id=$userId")
      val result =
        controller.index(receipt = None, expense = Some(expenseId.toString), dev_user_id = Some(userId.toString))(
          request
        )
      status(result) must be(OK)
      val body = contentAsString(result)
      body must include("99.00")
      body must include("2026-03-01")
      body must include("Dinner")
    }
  }

  "prod-mode" should {
    "return OK loading page when init_data is absent" in {
      val controller = makeController(prodConfig)
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=${UUID.randomUUID()}")
      val result =
        controller.index(
          receipt = Some(UUID.randomUUID().toString),
          expense = None,
          dev_user_id = None,
          init_data = None
        )(request)
      status(result) must be(OK)
      contentAsString(result) must include("init_data")
    }

    "return Unauthorized when init_data has invalid signature" in {
      val controller = makeController(prodConfig)
      val badInitData = "user=%7B%22id%22%3A123%7D&hash=badhash"
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=${UUID.randomUUID()}&init_data=...")
      val result =
        controller.index(
          receipt = Some(UUID.randomUUID().toString),
          expense = None,
          dev_user_id = None,
          init_data = Some(badInitData)
        )(request)
      status(result) must be(UNAUTHORIZED)
    }

    "return OK with prefilled form when init_data is correctly signed and account is linked" in {
      val userId = UUID.randomUUID()
      val telegramUserId = 54321L
      val receiptId = UUID.randomUUID()
      val initData = signedInitData("test", telegramUserId)
      val botUser = BotUser(UUID.randomUUID(), userId, telegramUserId, Instant.now())
      val telegramRepo = mock[TelegramRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantsRepo = mock[MerchantsRepository]
      val categoriesRepo = mock[CategoriesRepository]
      when(telegramRepo.findBotUserByTelegramId(telegramUserId)).thenReturn(Future.successful(Some(botUser)))
      when(receiptRepo.find(receiptId, userId)).thenReturn(Future.successful(Some(stubReceipt(receiptId, userId))))
      when(merchantsRepo.all(userId)).thenReturn(Future.successful(Nil))
      when(categoriesRepo.all(userId)).thenReturn(Future.successful(Nil))
      val controller = makeController(
        prodConfig,
        telegramRepo = telegramRepo,
        receiptRepo = receiptRepo,
        merchantsRepo = merchantsRepo,
        categoriesRepo = categoriesRepo
      )
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=$receiptId&init_data=...")
      val result =
        controller.index(
          receipt = Some(receiptId.toString),
          expense = None,
          dev_user_id = None,
          init_data = Some(initData)
        )(request)
      status(result) must be(OK)
      contentAsString(result) must include("12.50")
    }

    "return Unauthorized when init_data is correctly signed but account is not linked" in {
      val telegramUserId = 99999L
      val receiptId = UUID.randomUUID()
      val initData = signedInitData("test", telegramUserId)
      val telegramRepo = mock[TelegramRepository]
      when(telegramRepo.findBotUserByTelegramId(telegramUserId)).thenReturn(Future.successful(None))
      val controller = makeController(prodConfig, telegramRepo = telegramRepo)
      val request = FakeRequest(GET, s"/telegram/webapp?receipt=$receiptId&init_data=...")
      val result =
        controller.index(
          receipt = Some(receiptId.toString),
          expense = None,
          dev_user_id = None,
          init_data = Some(initData)
        )(request)
      status(result) must be(UNAUTHORIZED)
    }
  }

  "PUT /telegram/webapp/expenses/:id" should {
    "return Unauthorized when auth is missing in prod mode" in {
      val controller = makeController(prodConfig)
      val expenseId = UUID.randomUUID()
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId")
        .withBody(play.api.mvc.AnyContentAsEmpty)
      val result = controller.upsertExpense(expenseId.toString, dev_user_id = None, init_data = None)(request)
      status(result) must be(UNAUTHORIZED)
    }

    "return OK on happy path in dev mode" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val actionMock = mock[UpsertTelegramWebAppExpenseAction]
      when(actionMock(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(Right(())))
      val controller = makeController(devConfig, upsertExpenseAction = actionMock)
      val body = Json.obj("amount" -> 25.00, "date" -> "2026-05-10", "merchantName" -> "Supermarket")
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId?dev_user_id=$userId")
        .withJsonBody(body)
      val result =
        controller.upsertExpense(expenseId.toString, dev_user_id = Some(userId.toString), init_data = None)(request)
      status(result) must be(OK)
      contentAsString(result) must include("true")
    }

    "return OK in prod mode via X-Telegram-Init-Data header" in {
      val userId = UUID.randomUUID()
      val telegramUserId = 77777L
      val expenseId = UUID.randomUUID()
      val initData = signedInitData("test", telegramUserId)
      val botUser = BotUser(UUID.randomUUID(), userId, telegramUserId, java.time.Instant.now())
      val telegramRepo = mock[TelegramRepository]
      val actionMock = mock[UpsertTelegramWebAppExpenseAction]
      when(telegramRepo.findBotUserByTelegramId(telegramUserId)).thenReturn(Future.successful(Some(botUser)))
      when(actionMock(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(Right(())))
      val controller = makeController(prodConfig, telegramRepo = telegramRepo, upsertExpenseAction = actionMock)
      val body = Json.obj("amount" -> 9.99, "date" -> "2026-03-10", "merchantName" -> "Bakery")
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId")
        .withHeaders("X-Telegram-Init-Data" -> initData)
        .withJsonBody(body)
      val result = controller.upsertExpense(expenseId.toString, dev_user_id = None, init_data = None)(request)
      status(result) must be(OK)
    }

    "return UnprocessableEntity on validation error" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val actionMock = mock[UpsertTelegramWebAppExpenseAction]
      when(actionMock(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Future.successful(Left(UpsertExpenseError.Invalid("Amount must be positive"))))
      val controller = makeController(devConfig, upsertExpenseAction = actionMock)
      val body = Json.obj("amount" -> -1.0, "date" -> "2026-05-10", "merchantName" -> "Shop")
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId?dev_user_id=$userId")
        .withJsonBody(body)
      val result =
        controller.upsertExpense(expenseId.toString, dev_user_id = Some(userId.toString), init_data = None)(request)
      status(result) must be(UNPROCESSABLE_ENTITY)
      contentAsString(result) must include("Amount must be positive")
    }

    "return BadRequest when expense id is not a valid UUID" in {
      val userId = UUID.randomUUID()
      val controller = makeController(devConfig)
      val body = Json.obj("amount" -> 10.00, "date" -> "2026-05-10", "merchantName" -> "Shop")
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/not-a-uuid?dev_user_id=$userId")
        .withJsonBody(body)
      val result =
        controller.upsertExpense("not-a-uuid", dev_user_id = Some(userId.toString), init_data = None)(request)
      status(result) must be(BAD_REQUEST)
    }

    "return BadRequest when JSON body is missing" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val controller = makeController(devConfig)
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId?dev_user_id=$userId")
        .withBody(play.api.mvc.AnyContentAsEmpty)
      val result =
        controller.upsertExpense(expenseId.toString, dev_user_id = Some(userId.toString), init_data = None)(request)
      status(result) must be(BAD_REQUEST)
      contentAsString(result) must include("Expected JSON body")
    }

    "return BadRequest when date has invalid format" in {
      val userId = UUID.randomUUID()
      val expenseId = UUID.randomUUID()
      val controller = makeController(devConfig)
      val body = Json.obj("amount" -> 10.00, "date" -> "05/10/2026", "merchantName" -> "Shop")
      val request = FakeRequest(PUT, s"/telegram/webapp/expenses/$expenseId?dev_user_id=$userId")
        .withJsonBody(body)
      val result =
        controller.upsertExpense(expenseId.toString, dev_user_id = Some(userId.toString), init_data = None)(request)
      status(result) must be(BAD_REQUEST)
      contentAsString(result) must include("Invalid date format")
    }
  }
}
