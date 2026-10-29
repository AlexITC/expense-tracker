package controllers

import anorm.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, CreateExpenseFromRow}
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.merchants.CreateMerchant
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.models.jobs.BackgroundJobType
import net.wiringbits.repositories.BankStatementRepository
import net.wiringbits.repositories.models.BankStatementRow
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage}
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.db.Database
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import org.scalatest.OptionValues.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class BankStatementsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  private def makeRow(rowId: UUID, stmtId: UUID, amount: BigDecimal)(implicit now: Instant): BankStatementRow.Create =
    BankStatementRow.Create(
      rowId,
      stmtId,
      0,
      sampleDate,
      amount,
      "Test description",
      manuallyEdited = false,
      now,
      now,
      txnType = "purchase"
    )

  private def setupStmtWithRow(
      amount: BigDecimal = BigDecimal("-50.00")
  )(implicit client: net.wiringbits.api.ApiClient): (UUID, UUID) = {
    implicit val now: Instant = Instant.now()
    val userId = client.currentUser.futureValue.id
    val rowId = UuidCreator.getTimeOrderedEpoch()
    val stmtId = createBankStatementWithRows(userId, sid => List(makeRow(rowId, sid, amount)))
    (stmtId, rowId)
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }

  // Any status: the live executor may already have run the job against MockBedrockClient.
  private def suggestionsJobCount(stmtId: UUID): Long =
    app.injector.instanceOf[Database].withConnection { implicit conn =>
      SQL"""
        SELECT COUNT(*)
        FROM background_jobs
        WHERE type = ${BackgroundJobType.ResolveStatementSuggestions.entryName}
          AND payload ->> 'bankStatementId' = ${stmtId.toString}
        """.as(SqlParser.scalar[Long].single)
    }

  "GET /bank-statements" should {
    "return totalRows=0 and processedRows=0 for a statement with no rows" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      createBankStatement(client.currentUser.futureValue.id)

      val list = client.getBankStatements.futureValue
      list.data must have size 1
      list.data.head.totalRows must be(0)
      list.data.head.processedRows must be(0)
    }

    "return correct totalRows and processedRows=0 when no rows are processed" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()

      val list = client.getBankStatements.futureValue
      val stmt = list.data.find(_.id == stmtId).value
      stmt.totalRows must be(1)
      stmt.processedRows must be(0)
    }

    "count ignored rows as processed" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      client.ignoreRow(stmtId, rowId).futureValue

      val list = client.getBankStatements.futureValue
      val stmt = list.data.find(_.id == stmtId).value
      stmt.totalRows must be(1)
      stmt.processedRows must be(1)
    }

    "count expense-linked rows as processed" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      client.createExpenseFromRow(stmtId, rowId, req).futureValue

      val list = client.getBankStatements.futureValue
      val stmt = list.data.find(_.id == stmtId).value
      stmt.totalRows must be(1)
      stmt.processedRows must be(1)
    }

    "not include other users statements" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val list2 = client2.getBankStatements.futureValue
      list2.data.map(_.id) must not contain stmtId
    }
  }

  "POST /bank-statements/:id/rows/:rowId/ignore" should {
    "ignore an unprocessed row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      client.ignoreRow(stmtId, rowId).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.ignoredAt must not be None
      stmt.statementRows.head.expenseId must be(None)
    }

    "fail when the bank statement does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.ignoreRow(stmtId, rowId).expectError
      error must be("Bank statement not found")
    }

    "fail when the row does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)

      val error = client.ignoreRow(stmtId, UUID.randomUUID()).expectError
      error must be("Bank statement row not found")
    }

    "fail when the row already has an expense linked" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      client.createExpenseFromRow(stmtId, rowId, req).futureValue

      val error = client.ignoreRow(stmtId, rowId).expectError
      error must be("Bank statement row is already linked to an expense")
    }
  }

  "DELETE /bank-statements/:id/rows/:rowId/ignore" should {
    "unignore an ignored row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      client.ignoreRow(stmtId, rowId).futureValue
      client.unignoreRow(stmtId, rowId).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.ignoredAt must be(None)
    }

    "fail when the bank statement does not belong to the user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()
      client.ignoreRow(stmtId, rowId).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.unignoreRow(stmtId, rowId).expectError
      error must be("Bank statement not found")
    }

    "fail when the row does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)

      val error = client.unignoreRow(stmtId, UUID.randomUUID()).expectError
      error must be("Bank statement row not found")
    }
  }

  "ignoredAt interaction with expense linking" should {
    "clear ignoredAt when an expense is linked to a previously ignored row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      client.ignoreRow(stmtId, rowId).futureValue
      val ignored = client.getBankStatement(stmtId).futureValue
      ignored.statementRows.head.ignoredAt must not be None

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      client.createExpenseFromRow(stmtId, rowId, req).futureValue

      val linked = client.getBankStatement(stmtId).futureValue
      linked.statementRows.head.expenseId must not be None
      linked.statementRows.head.ignoredAt must be(None)
    }

    "GET /bank-statements/:id returns ignoredAt in row data" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val before = client.getBankStatement(stmtId).futureValue
      before.statementRows.head.ignoredAt must be(None)

      client.ignoreRow(stmtId, rowId).futureValue

      val after = client.getBankStatement(stmtId).futureValue
      after.statementRows.head.ignoredAt must not be None
    }
  }

  "GET /bank-statements/:id" should {
    "return the linked expense's merchant and category only on the linked row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      implicit val now: Instant = Instant.now()
      val userId = client.currentUser.futureValue.id
      val linkedRowId = UuidCreator.getTimeOrderedEpoch()
      val unlinkedRowId = UuidCreator.getTimeOrderedEpoch()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            makeRow(linkedRowId, sid, BigDecimal("-50.00")),
            makeRow(unlinkedRowId, sid, BigDecimal("-20.00")).copy(index = 1)
          )
      )
      val category = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val merchant = client.createMerchant(CreateMerchant.Request("Supermarket", None, None)).futureValue
      val req = CreateExpenseFromRow.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        Some(merchant.id),
        None,
        List(CreateExpenseFromRow.Split(category.id, BigDecimal("50.00")))
      )
      client.createExpenseFromRow(stmtId, linkedRowId, req).futureValue

      val rows = client.getBankStatement(stmtId).futureValue.statementRows
      val linked = rows.find(_.id == linkedRowId).value
      linked.expenseMerchantId must be(Some(merchant.id))
      linked.expenseCategoryId must be(Some(category.id))
      val unlinked = rows.find(_.id == unlinkedRowId).value
      unlinked.expenseMerchantId must be(None)
      unlinked.expenseCategoryId must be(None)
    }

    "return no AI suggestions for a fresh statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()

      client.getBankStatement(stmtId).futureValue.aiSuggestions must be(None)
    }

    "return the stored AI suggestions" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val (stmtId, rowId) = setupStmtWithRow()
      val suggestions = AiSuggestions(
        status = AiSuggestionsStatus.Done,
        model = "model-x",
        rows = List(AiSuggestions.RowSuggestion(rowId, Some(UUID.randomUUID()), None))
      )
      app.injector
        .instanceOf[BankStatementRepository]
        .setAiSuggestions(stmtId, userId, suggestions, Instant.now())
        .futureValue

      client.getBankStatement(stmtId).futureValue.aiSuggestions must be(Some(suggestions))
    }
  }

  "POST /bank-statements/:id/ai-suggestions" should {
    "return pending suggestions for a fresh statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()

      val response = client.regenerateBankStatementAiSuggestions(stmtId).futureValue
      response.aiSuggestions.status must be(AiSuggestionsStatus.Pending)
      response.aiSuggestions.rows must be(Nil)
    }

    "enqueue a ResolveStatementSuggestions job for the statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()
      // createBankStatementWithRows already enqueues one job per statement
      suggestionsJobCount(stmtId) must be(1L)

      client.regenerateBankStatementAiSuggestions(stmtId).futureValue
      suggestionsJobCount(stmtId) must be(2L)
    }

    "fail for another user's statement without enqueuing a job" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, _) = setupStmtWithRow()
      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      client2.regenerateBankStatementAiSuggestions(stmtId).expectError must be("Bank statement not found")
      suggestionsJobCount(stmtId) must be(1L)
    }

    "fail for an unknown statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.regenerateBankStatementAiSuggestions(UUID.randomUUID()).expectError must be("Bank statement not found")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getBankStatements.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }
}
