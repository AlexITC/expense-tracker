package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.{CreateRefundFromRow, GetBankStatement}
import net.wiringbits.api.models.refunds.{CreateRefund, LinkRefundToExpense, UpdateRefund}
import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.repositories.models.BankStatementRow
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage}
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class RefundsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

  private val tokenGenerator = mock[TokenGenerator]
  private val emailApi = mock[EmailApi]
  when(emailApi.sendEmail(any[EmailRequest]())).thenReturn(Future.unit)
  private val captchaApi = mock[ReCaptchaApi]
  when(captchaApi.verify(any[Captcha]())).thenReturn(Future.successful(true))

  override def guiceApplicationBuilder(container: PostgreSQLContainer): GuiceApplicationBuilder =
    super
      .guiceApplicationBuilder(container)
      .overrides(
        inject.bind[EmailApi].to(emailApi),
        inject.bind[ReCaptchaApi].to(captchaApi),
        inject.bind[TokenGenerator].to(tokenGenerator),
        inject.bind[BlobStorage].toInstance(new NoOpBlobStorage())
      )

  private val sampleDate = LocalDate.of(2026, 1, 15)

  private def createRefundRequest(
      amount: BigDecimal = BigDecimal("100.00"),
      date: LocalDate = sampleDate
  ) = CreateRefund.Request(
    amount = amount,
    date = date,
    merchantId = None,
    categoryId = None,
    expenseId = None,
    notes = None
  )

  private def setupCreditRow()(implicit client: net.wiringbits.api.ApiClient): (UUID, UUID) = {
    implicit val now: Instant = Instant.now()
    val userId = client.currentUser.futureValue.id
    val rowId = UuidCreator.getTimeOrderedEpoch()
    val stmtId = createBankStatementWithRows(
      userId,
      sid =>
        List(
          BankStatementRow
            .Create(
              rowId,
              sid,
              0,
              sampleDate,
              BigDecimal("100.00"),
              "Credit",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
        )
    )
    (stmtId, rowId)
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }

  // ── GET /refunds ────────────────────────────────────────────────────────────

  "GET /refunds" should {
    "return an empty list when user has no refunds" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val response = client.getRefunds.futureValue
      response.data must be(empty)
    }

    "return the user's refunds" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createRefund(createRefundRequest(BigDecimal("10.00"))).futureValue
      client.createRefund(createRefundRequest(BigDecimal("20.00"))).futureValue

      val response = client.getRefunds.futureValue
      response.data.length must be(2)
    }

    "not return another user's refunds" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createRefund(createRefundRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      client2.getRefunds.futureValue.data must be(empty)
    }
  }

  // ── POST /refunds ────────────────────────────────────────────────────────────

  "POST /refunds" should {
    "create a refund successfully" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.createRefund(createRefundRequest(BigDecimal("75.00"))).futureValue
      response.amount must be(BigDecimal("75.00"))
      response.date must be(sampleDate)
    }

    "fail when amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val error = client.createRefund(createRefundRequest(BigDecimal("0"))).expectError
      error must be("Amount must be greater than 0")
    }

    "fail when amount is negative" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val error = client.createRefund(createRefundRequest(BigDecimal("-10.00"))).expectError
      error must be("Amount must be greater than 0")
    }
  }

  // ── PUT /refunds/:id ─────────────────────────────────────────────────────────

  "PUT /refunds/:id" should {
    "update a refund successfully" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createRefund(createRefundRequest(BigDecimal("50.00"))).futureValue

      client
        .updateRefund(
          created.id,
          UpdateRefund.Request(BigDecimal("99.99"), sampleDate, None, None, Some("updated"))
        )
        .futureValue

      val refunds = client.getRefunds.futureValue
      val updated = refunds.data.find(_.id == created.id).get
      updated.amount must be(BigDecimal("99.99"))
      updated.notes must be(Some("updated"))
    }

    "fail when refund does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val error = client
        .updateRefund(UUID.randomUUID(), UpdateRefund.Request(BigDecimal("10.00"), sampleDate, None, None, None))
        .expectError
      error must be("Refund not found")
    }
  }

  // ── DELETE /refunds/:id ──────────────────────────────────────────────────────

  "DELETE /refunds/:id" should {
    "delete a refund successfully" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createRefund(createRefundRequest()).futureValue

      client.deleteRefund(created.id).futureValue

      val refunds = client.getRefunds.futureValue
      refunds.data must be(empty)
    }

    "fail when refund does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val error = client.deleteRefund(UUID.randomUUID()).expectError
      error must be("Refund not found")
    }
  }

  // ── POST /refunds/:id/link-expense ───────────────────────────────────────────

  "POST /refunds/:id/link-expense" should {
    "link a refund to an expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val refund = client.createRefund(createRefundRequest()).futureValue
      val expense = client
        .createExpense(CreateExpense.Request(BigDecimal("100.00"), sampleDate, None, None, None, Nil))
        .futureValue

      client.linkRefundToExpense(refund.id, LinkRefundToExpense.Request(expense.id)).futureValue

      val refunds = client.getRefunds.futureValue
      refunds.data.find(_.id == refund.id).get.expenseId must be(Some(expense.id))
    }

    "fail when the refund does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val expense = client
        .createExpense(CreateExpense.Request(BigDecimal("100.00"), sampleDate, None, None, None, Nil))
        .futureValue
      val error = client.linkRefundToExpense(UUID.randomUUID(), LinkRefundToExpense.Request(expense.id)).expectError
      error must be("Refund not found")
    }
  }

  // ── DELETE /refunds/:id/link-expense ─────────────────────────────────────────

  "DELETE /refunds/:id/link-expense" should {
    "unlink a refund from its expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val refund = client.createRefund(createRefundRequest()).futureValue
      val expense = client
        .createExpense(CreateExpense.Request(BigDecimal("100.00"), sampleDate, None, None, None, Nil))
        .futureValue

      client.linkRefundToExpense(refund.id, LinkRefundToExpense.Request(expense.id)).futureValue
      client.unlinkRefundFromExpense(refund.id).futureValue

      val refunds = client.getRefunds.futureValue
      refunds.data.find(_.id == refund.id).get.expenseId must be(None)
    }
  }

  // ── POST /bank-statements/:id/rows/:rowId/create-refund ──────────────────────

  "POST /bank-statements/:id/rows/:rowId/create-refund" should {
    "create a refund from a credit row and link it" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupCreditRow()

      val req = CreateRefundFromRow.Request(BigDecimal("100.00"), sampleDate, None, None, None, None)
      val resp = client.createRefundFromRow(stmtId, rowId, req).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.refundId must be(Some(resp.refundId))

      val refunds = client.getRefunds.futureValue
      refunds.data.length must be(1)
      refunds.data.head.id must be(resp.refundId)
    }

    "fail for debit rows (amount < 0)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      implicit val now: Instant = Instant.now()
      val userId = client.currentUser.futureValue.id
      val rowId = UuidCreator.getTimeOrderedEpoch()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            BankStatementRow.Create(
              rowId,
              sid,
              0,
              sampleDate,
              BigDecimal("-50.00"),
              "Debit",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
          )
      )

      val req = CreateRefundFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, None)
      val error = client.createRefundFromRow(stmtId, rowId, req).expectError
      error must be("Only credit rows (amount > 0) can create refunds")
    }

    "clear ignoredAt when creating refund from a previously ignored credit row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupCreditRow()

      client.ignoreRow(stmtId, rowId).futureValue
      val ignoredStmt = client.getBankStatement(stmtId).futureValue
      ignoredStmt.statementRows.head.ignoredAt must not be None

      val req = CreateRefundFromRow.Request(BigDecimal("100.00"), sampleDate, None, None, None, None)
      client.createRefundFromRow(stmtId, rowId, req).futureValue

      val linked = client.getBankStatement(stmtId).futureValue
      linked.statementRows.head.refundId must not be None
      linked.statementRows.head.ignoredAt must be(None)
    }
  }

  // ── ignore row with refund linked ────────────────────────────────────────────

  "POST /bank-statements/:id/rows/:rowId/ignore with refund linked" should {
    "fail with specific message when a refund is already linked" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupCreditRow()

      val req = CreateRefundFromRow.Request(BigDecimal("100.00"), sampleDate, None, None, None, None)
      client.createRefundFromRow(stmtId, rowId, req).futureValue

      val error = client.ignoreRow(stmtId, rowId).expectError
      error must be("Bank statement row is already linked to a refund")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getRefunds.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }
}
