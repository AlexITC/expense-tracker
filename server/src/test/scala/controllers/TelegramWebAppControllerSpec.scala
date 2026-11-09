package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.apis.TelegramApi
import net.wiringbits.common.models.Name
import net.wiringbits.core.SharedPostgres
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Expense, Receipt, User}
import net.wiringbits.repositories.{ExpensesRepository, ReceiptRepository, TelegramRepository, UsersRepository}
import play.api.Application
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import utils.{FakeTelegramApi, RandomData}

import java.net.URLEncoder
import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Exercises the real controller wired by Guice against the real database. The only fake is TelegramApi (an external
  * service). The suite app runs in web-app dev mode (auth via `dev_user_id`); `prodApp` is a second app in prod mode,
  * for the signed-initData paths.
  */
class TelegramWebAppControllerSpec extends PlayPostgresSpec {

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .configure("telegram.botToken" -> "test", "telegram.webAppDevMode" -> true)
      .overrides(inject.bind[TelegramApi].toInstance(new FakeTelegramApi()))

  private lazy val prodApp: Application =
    guiceApplicationBuilder(SharedPostgres.container).configure("telegram.webAppDevMode" -> false).build()

  // ── helpers ────────────────────────────────────────────────────────────────

  private def createUserId()(implicit app: Application): UUID = {
    val id = UUID.randomUUID()
    app.injector
      .instanceOf[UsersRepository]
      .create(
        User.CreateUser(
          id = id,
          email = RandomData.randomEmail(),
          name = Name.trusted("Sample"),
          hashedPassword = "password",
          verifyEmailToken = "token"
        )
      )
      .futureValue
    id
  }

  private def createParsedReceipt(
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      merchantName: String
  )(implicit app: Application): UUID = {
    val receiptRepo = app.injector.instanceOf[ReceiptRepository]
    val now = Instant.now()
    val docId = UUID.randomUUID()
    val document = Document.Create(
      id = docId,
      userId = userId,
      sha256 = "sha-" + docId.toString,
      storageKey = "",
      kind = DocumentKind.Receipt,
      filename = "receipt.pdf",
      createdAt = now,
      updatedAt = now
    )
    val receiptId = UUID.randomUUID()
    val receipt = Receipt.Create(
      id = receiptId,
      userId = userId,
      documentId = docId,
      manuallyEdited = false,
      status = DocumentStatus.Ingested,
      createdAt = now,
      updatedAt = now
    )
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = BackgroundJobPayload.ProcessDocument(docId),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    receiptRepo.createWithDocument(document, receipt, job).futureValue
    receiptRepo.updateParsed(receiptId, Some(amount), Some(date), None, Some(merchantName), None, None).futureValue
    receiptId
  }

  private def createExpenseId(
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      notes: Option[String]
  )(implicit app: Application): UUID = {
    val id = UUID.randomUUID()
    val now = Instant.now()
    app.injector
      .instanceOf[ExpensesRepository]
      .create(
        Expense.Create(
          id = id,
          userId = userId,
          amount = amount,
          date = date,
          time = None,
          merchantId = None,
          notes = notes,
          createdAt = now,
          updatedAt = now
        ),
        Nil
      )
      .futureValue
    id
  }

  private def linkBotUser(userId: UUID, telegramUserId: Long)(implicit app: Application): Unit =
    app.injector.instanceOf[TelegramRepository].createBotUser(UUID.randomUUID(), userId, telegramUserId).futureValue

  /** Builds a correctly-signed Telegram initData string for the given botToken and telegramUserId. */
  private def signedInitData(botToken: String, telegramUserId: Long): String = {
    val userJson = s"""{"id":$telegramUserId}"""
    val dataCheckString = s"user=$userJson"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec("WebAppData".getBytes("UTF-8"), "HmacSHA256"))
    val secretKey = mac.doFinal(botToken.getBytes("UTF-8"))
    mac.init(new SecretKeySpec(secretKey, "HmacSHA256"))
    val hash = mac.doFinal(dataCheckString.getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    s"user=${URLEncoder.encode(userJson, "UTF-8")}&hash=$hash"
  }

  private def enc(s: String): String = URLEncoder.encode(s, "UTF-8")

  // ── dev mode (auth via dev_user_id) ──────────────────────────────────────────

  "GET /telegram/webapp (dev mode)" should {
    "return Unauthorized when dev_user_id is missing" in {
      status(GET("/telegram/webapp?receipt=abc")) must be(UNAUTHORIZED)
    }

    "return Unauthorized when dev_user_id is not a valid UUID" in {
      status(GET("/telegram/webapp?receipt=abc&dev_user_id=notauuid")) must be(UNAUTHORIZED)
    }

    "return BadRequest when neither receipt nor expense is provided" in {
      val userId = createUserId()
      status(GET(s"/telegram/webapp?dev_user_id=$userId")) must be(BAD_REQUEST)
    }

    "return BadRequest when receipt ID is not a valid UUID" in {
      val userId = createUserId()
      status(GET(s"/telegram/webapp?receipt=notauuid&dev_user_id=$userId")) must be(BAD_REQUEST)
    }

    "return NotFound when receipt does not exist" in {
      val userId = createUserId()
      status(GET(s"/telegram/webapp?receipt=${UUID.randomUUID()}&dev_user_id=$userId")) must be(NOT_FOUND)
    }

    "return OK with prefilled form for a receipt" in {
      val userId = createUserId()
      val receiptId = createParsedReceipt(userId, BigDecimal("12.50"), LocalDate.of(2026, 1, 15), "Coffee Shop")
      val result = GET(s"/telegram/webapp?receipt=$receiptId&dev_user_id=$userId")
      status(result) must be(OK)
      val body = contentAsString(result)
      body must include("12.50")
      body must include("2026-01-15")
      body must include("Coffee Shop")
    }

    "return OK with prefilled form for an expense" in {
      val userId = createUserId()
      val expenseId = createExpenseId(userId, BigDecimal("99.00"), LocalDate.of(2026, 3, 1), Some("Dinner"))
      val result = GET(s"/telegram/webapp?expense=$expenseId&dev_user_id=$userId")
      status(result) must be(OK)
      val body = contentAsString(result)
      body must include("99.00")
      body must include("2026-03-01")
      body must include("Dinner")
    }
  }

  // ── prod mode (auth via signed initData) ─────────────────────────────────────

  "GET /telegram/webapp (prod mode)" should {
    "return OK loading page when init_data is absent" in {
      implicit val a: Application = prodApp
      val result = GET(s"/telegram/webapp?receipt=${UUID.randomUUID()}")
      status(result) must be(OK)
      contentAsString(result) must include("init_data")
    }

    "return Unauthorized when init_data has invalid signature" in {
      implicit val a: Application = prodApp
      val bad = "user=%7B%22id%22%3A123%7D&hash=badhash"
      status(GET(s"/telegram/webapp?receipt=${UUID.randomUUID()}&init_data=${enc(bad)}")) must be(UNAUTHORIZED)
    }

    "return OK with prefilled form when init_data is correctly signed and account is linked" in {
      implicit val a: Application = prodApp
      val telegramUserId = RandomData.randomTelegramUserId()
      val userId = createUserId()
      linkBotUser(userId, telegramUserId)
      val receiptId = createParsedReceipt(userId, BigDecimal("12.50"), LocalDate.of(2026, 1, 15), "Coffee Shop")
      val initData = signedInitData("test", telegramUserId)
      val result = GET(s"/telegram/webapp?receipt=$receiptId&init_data=${enc(initData)}")
      status(result) must be(OK)
      contentAsString(result) must include("12.50")
    }

    "return Unauthorized when init_data is correctly signed but account is not linked" in {
      implicit val a: Application = prodApp
      val telegramUserId = RandomData.randomTelegramUserId()
      val initData = signedInitData("test", telegramUserId)
      status(GET(s"/telegram/webapp?receipt=${UUID.randomUUID()}&init_data=${enc(initData)}")) must be(UNAUTHORIZED)
    }
  }

  // ── PUT /telegram/webapp/expenses/:id ────────────────────────────────────────

  "PUT /telegram/webapp/expenses/:id" should {
    "return Unauthorized when auth is missing in prod mode" in {
      implicit val a: Application = prodApp
      status(PUT(s"/telegram/webapp/expenses/${UUID.randomUUID()}")) must be(UNAUTHORIZED)
    }

    "return OK on happy path in dev mode" in {
      val userId = createUserId()
      val body = """{"amount":25.00,"date":"2026-05-10","merchantName":"Supermarket","category":"Groceries"}"""
      val result = PUT(s"/telegram/webapp/expenses/${UUID.randomUUID()}?dev_user_id=$userId", body)
      status(result) must be(OK)
      contentAsString(result) must include("true")
    }

    "return OK in prod mode via X-Telegram-Init-Data header" in {
      implicit val a: Application = prodApp
      val telegramUserId = RandomData.randomTelegramUserId()
      val userId = createUserId()
      linkBotUser(userId, telegramUserId)
      val initData = signedInitData("test", telegramUserId)
      val body = """{"amount":9.99,"date":"2026-03-10","merchantName":"Bakery","category":"Groceries"}"""
      val result =
        PUT(s"/telegram/webapp/expenses/${UUID.randomUUID()}", body, "X-Telegram-Init-Data" -> initData)
      status(result) must be(OK)
    }

    "return UnprocessableEntity on validation error" in {
      val userId = createUserId()
      val body = """{"amount":-1.0,"date":"2026-05-10","merchantName":"Shop"}"""
      status(PUT(s"/telegram/webapp/expenses/${UUID.randomUUID()}?dev_user_id=$userId", body)) must be(
        UNPROCESSABLE_ENTITY
      )
    }

    "return BadRequest when expense id is not a valid UUID" in {
      val userId = createUserId()
      val body = """{"amount":10.00,"date":"2026-05-10","merchantName":"Shop"}"""
      status(PUT(s"/telegram/webapp/expenses/not-a-uuid?dev_user_id=$userId", body)) must be(BAD_REQUEST)
    }

    "return BadRequest when JSON body is missing" in {
      val userId = createUserId()
      val request =
        FakeRequest("PUT", s"/telegram/webapp/expenses/${UUID.randomUUID()}?dev_user_id=$userId").withTextBody("x")
      val result = route(app, request).value
      status(result) must be(BAD_REQUEST)
      contentAsString(result) must include("Expected JSON body")
    }

    "return BadRequest when date has invalid format" in {
      val userId = createUserId()
      val body = """{"amount":10.00,"date":"05/10/2026","merchantName":"Shop"}"""
      val result = PUT(s"/telegram/webapp/expenses/${UUID.randomUUID()}?dev_user_id=$userId", body)
      status(result) must be(BAD_REQUEST)
      contentAsString(result) must include("Invalid date format")
    }
  }
}
