package controllers

import anorm.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.{
  AiSuggestions,
  AiSuggestionsStatus,
  CreateExpenseFromRow,
  LinkRowToExpense,
  SetAccountAlias
}
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
import utils.{ExpenseUtils, LoginUtils}

import org.scalatest.OptionValues.*

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class BankStatementsControllerSpec extends PlayPostgresSpec with LoginUtils with ExpenseUtils with MockitoSugar {

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

  // The nickname of the statement's account, read from the account-aliases list (keyed by the account tuple). None when
  // the account has no (non-empty) nickname — the list leaves empty ones out.
  private def aliasOf(stmtId: UUID)(implicit client: net.wiringbits.api.ApiClient): Option[String] = {
    val s = client.getBankStatement(stmtId).futureValue
    client.getBankStatementAccountAliases.futureValue.accounts
      .find(a =>
        a.bankName == s.bankName && a.productType == s.productType &&
          a.accountLast4 == s.accountLast4 && a.currency == s.currency
      )
      .map(_.alias)
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

      val req = CreateExpenseFromRow.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        None,
        None,
        fullRowSplit(BigDecimal("50.00")).futureValue
      )
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

    "list overlapping statements of the same account in overlappingStatementIds" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val a = createBankStatement(userId)
      val b = createBankStatement(userId)
      val otherAccount = createBankStatement(userId, defaultStatementMetadata.copy(accountLast4 = "9999"))

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val otherUserStmt = createBankStatement(client2.currentUser.futureValue.id)

      val byId = client.getBankStatements.futureValue.data.map(s => s.id -> s.overlappingStatementIds).toMap
      byId(a) must be(List(b))
      byId(b) must be(List(a))
      byId(otherAccount) must be(empty)
      byId.values.flatten must not contain otherUserStmt
      client2.getBankStatements.futureValue.data.flatMap(_.overlappingStatementIds) must be(empty)
    }

    "include the account alias" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val stmtId = createBankStatement(userId)
      val unnamedId = createBankStatement(userId, defaultStatementMetadata.copy(accountLast4 = "1111"))
      app.injector
        .instanceOf[BankStatementRepository]
        .setAccountAlias(stmtId, userId, "Travel card", Instant.now())
        .futureValue

      aliasOf(stmtId) must be(Some("Travel card"))
      aliasOf(unnamedId) must be(None)
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

      val req = CreateExpenseFromRow.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        None,
        None,
        fullRowSplit(BigDecimal("50.00")).futureValue
      )
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

  "POST /bank-statements/:id/rows/:rowId/link" should {
    "fail when the expense is already linked to another row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      implicit val now: Instant = Instant.now()
      val userId = client.currentUser.futureValue.id
      val firstRowId = UuidCreator.getTimeOrderedEpoch()
      val secondRowId = UuidCreator.getTimeOrderedEpoch()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            makeRow(firstRowId, sid, BigDecimal("-50.00")),
            makeRow(secondRowId, sid, BigDecimal("-50.00")).copy(index = 1)
          )
      )
      val req = CreateExpenseFromRow.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        None,
        None,
        fullRowSplit(BigDecimal("50.00")).futureValue
      )
      val expenseId = client.createExpenseFromRow(stmtId, firstRowId, req).futureValue.expenseId

      val error = client.linkRowToExpense(stmtId, secondRowId, LinkRowToExpense.Request(expenseId)).expectError
      error must be("That expense is already linked to another row")
    }
  }

  "ignoredAt interaction with expense linking" should {
    "clear ignoredAt when an expense is linked to a previously ignored row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      client.ignoreRow(stmtId, rowId).futureValue
      val ignored = client.getBankStatement(stmtId).futureValue
      ignored.statementRows.head.ignoredAt must not be None

      val req = CreateExpenseFromRow.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        None,
        None,
        fullRowSplit(BigDecimal("50.00")).futureValue
      )
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

    "include the account alias" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val stmtId = createBankStatement(userId)
      app.injector
        .instanceOf[BankStatementRepository]
        .setAccountAlias(stmtId, userId, "Travel card", Instant.now())
        .futureValue

      aliasOf(stmtId) must be(Some("Travel card"))
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

  "PUT /bank-statements/:id/account-alias" should {
    "name every statement of the same account" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val stmtId = createBankStatement(userId)
      val sameAccountId = createBankStatement(userId)
      val otherAccountId = createBankStatement(userId, defaultStatementMetadata.copy(accountLast4 = "1111"))

      val response = client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Travel card")).futureValue
      response.alias must be("Travel card")

      aliasOf(stmtId) must be(Some("Travel card"))
      aliasOf(sameAccountId) must be(Some("Travel card"))
      aliasOf(otherAccountId) must be(None)
    }

    "replace an existing nickname" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)

      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Travel card")).futureValue
      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Daily card")).futureValue

      aliasOf(stmtId) must be(Some("Daily card"))
    }

    "store an empty nickname" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)
      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Travel card")).futureValue

      val response = client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("")).futureValue
      response.alias must be("")

      aliasOf(stmtId) must be(None)
    }

    "accept a nickname of exactly the maximum length" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)
      val alias = "a" * SetAccountAlias.MaxAliasLength

      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request(alias)).futureValue

      aliasOf(stmtId) must be(Some(alias))
    }

    "reject a nickname over the maximum length without changing the stored one" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)
      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Travel card")).futureValue

      val error = client
        .setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("a" * (SetAccountAlias.MaxAliasLength + 1)))
        .expectError
      error must be("Nickname must be 60 characters or less")

      aliasOf(stmtId) must be(Some("Travel card"))
    }

    "not name another user's account" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val stmtId = createBankStatement(client.currentUser.futureValue.id)
      client.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Travel card")).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.setBankStatementAccountAlias(stmtId, SetAccountAlias.Request("Hijacked")).expectError
      error must be("Bank statement not found")

      aliasOf(stmtId) must be(Some("Travel card"))
    }
  }
}
