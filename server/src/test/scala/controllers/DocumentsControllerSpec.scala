package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.actions.documents.{ParseReceiptAction, PollReceiptAction, ProcessDocumentAction}
import net.wiringbits.api.models.documents.{DocumentStatus => ApiDocumentStatus}
import net.wiringbits.api.models.bankstatements.{ConfirmBankStatementRow, CreateExpenseFromRow, LinkRowToExpense}
import net.wiringbits.api.models.expenses.CreateExpense
import net.wiringbits.api.models.categories.CreateCategory
import net.wiringbits.api.models.merchants.CreateMerchant
import net.wiringbits.api.models.receipts.{ConfirmReceipt, LinkReceiptToExpense}
import net.wiringbits.repositories.models.BankStatementRow
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage}
import net.wiringbits.util.TokenGenerator
import com.github.f4b6a3.uuid.UuidCreator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class DocumentsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  private val sampleBytes: Array[Byte] = "fake-pdf-content".getBytes("UTF-8")
  private val sampleDate = LocalDate.of(2024, 1, 15)

  "POST /receipts" should {
    "upload a receipt and return its id" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val response = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      response.id must not be null
    }

    "return the existing receipt when the same content is uploaded again" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val second = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      second.id must be(first.id)
    }

    "allow different users to upload the same file independently" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val second = client2.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      second.id must not be first.id
    }
  }

  "GET /receipts" should {
    "return an empty list when user has no receipts" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.getReceipts.futureValue.data must be(empty)
    }

    "return the user's receipts" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.uploadReceipt(sampleBytes, "receipt", "a.pdf").futureValue
      client.uploadReceipt("other".getBytes, "receipt", "b.pdf").futureValue
      client.getReceipts.futureValue.data.length must be(2)
    }

    "not return another user's receipts" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      client2.getReceipts.futureValue.data must be(empty)
    }
  }

  "GET /receipts/:id" should {
    "return the receipt" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val receipt = client.getReceipt(uploaded.id).futureValue
      receipt.documentFilename must be("test.pdf")
    }

    "fail when the receipt belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.getReceipt(uploaded.id).expectError
      error must be("Receipt not found")
    }
  }

  "ProcessDocument background job" should {
    "process a receipt to Parsed status via async lifecycle" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)

      val receipt = client.getReceipt(uploaded.id).futureValue
      receipt.documentStatus must be(ApiDocumentStatus.Parsed)
    }

    "be idempotent — reprocessing a Parsed receipt changes nothing" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)
      app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue

      val receipt = client.getReceipt(uploaded.id).futureValue
      receipt.documentStatus must be(ApiDocumentStatus.Parsed)
    }
  }

  "POST /receipts/:id/confirm" should {
    "create an expense and link it to the receipt" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val confirmed = client.confirmReceipt(uploaded.id, req).futureValue
      confirmed.expenseId must not be empty

      val receipt = client.getReceipt(uploaded.id).futureValue
      receipt.expenseId must be(confirmed.expenseId)
    }

    "fail when receipt is not Parsed" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val error = client.confirmReceipt(uploaded.id, req).expectError
      error must be("Receipt is not in Parsed status")
    }

    "fail when receipt is already confirmed" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      client.confirmReceipt(uploaded.id, req).futureValue
      val error = client.confirmReceipt(uploaded.id, req).expectError
      error must be("Receipt is already linked to an expense — unlink first")
    }

    "fail when receipt belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val error = client2.confirmReceipt(uploaded.id, req).expectError
      error must be("Receipt not found")
    }
  }

  "POST /receipts/:id/confirm splits" should {
    "create expense with partial splits (sum < amount)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val receiptId = setupParsedReceipt()

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("100.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(CreateExpense.Split(cat.id, BigDecimal("40.00")))
      )
      val confirmed = client.confirmReceipt(receiptId, req).futureValue
      confirmed.expenseId must not be empty

      val expense = client.getExpenses.futureValue.data.find(_.id == confirmed.expenseId.get).get
      expense.splits must have size 1
      expense.splits.head.amount must be(BigDecimal("40.00"))
    }

    "create expense with splits summing exactly to amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val receiptId = setupParsedReceipt()

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("50.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(CreateExpense.Split(cat.id, BigDecimal("50.00")))
      )
      client.confirmReceipt(receiptId, req).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data must have size 1
      expenses.data.head.splits.head.amount must be(BigDecimal("50.00"))
    }

    "reject duplicate category in splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val receiptId = setupParsedReceipt()

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("100.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(
          CreateExpense.Split(cat.id, BigDecimal("30.00")),
          CreateExpense.Split(cat.id, BigDecimal("20.00"))
        )
      )
      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Duplicate category in splits")
    }

    "reject split with amount <= 0" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val receiptId = setupParsedReceipt()

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("100.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(CreateExpense.Split(cat.id, BigDecimal("0.00")))
      )
      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Split amount must be greater than 0")
    }

    "reject splits summing to more than expense amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat1 = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val cat2 = client.createCategory(CreateCategory.Request("Transport")).futureValue
      val receiptId = setupParsedReceipt()

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("50.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(
          CreateExpense.Split(cat1.id, BigDecimal("30.00")),
          CreateExpense.Split(cat2.id, BigDecimal("30.00"))
        )
      )
      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Sum of split amounts exceeds expense amount")
    }

    "reject split category belonging to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val otherCat = client2.createCategory(CreateCategory.Request("Other")).futureValue

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("50.00")),
        Some(sampleDate),
        None,
        None,
        None,
        List(CreateExpense.Split(otherCat.id, BigDecimal("20.00")))
      )
      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Category not found")
    }
  }

  "DELETE /receipts/:id" should {
    "delete a receipt" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      client.deleteReceipt(uploaded.id).futureValue
      client.getReceipts.futureValue.data must be(empty)
    }

    "fail when receipt belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteReceipt(uploaded.id).expectError
      error must be("Receipt not found")
    }

    "fail when receipt does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val error = client.deleteReceipt(UUID.randomUUID()).expectError
      error must be("Receipt not found")
    }

    "leave the expense intact after receipt deletion" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val confirmed = client.confirmReceipt(uploaded.id, req).futureValue
      client.deleteReceipt(uploaded.id).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.map(_.id) must contain(confirmed.expenseId.get)
    }
  }

  "expense delete" should {
    "set expense_id to null on the related receipt (ON DELETE SET NULL)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val uploaded = client.uploadReceipt(sampleBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploaded.id).futureValue.documentId

      driveReceiptToCompletion(documentId)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val confirmed = client.confirmReceipt(uploaded.id, req).futureValue
      client.deleteExpense(confirmed.expenseId.get).futureValue

      val receipt = client.getReceipt(uploaded.id).futureValue
      receipt.expenseId must be(None)
    }
  }

  "bank statement" should {
    "confirm a bank statement row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val now = Instant.now()
      val rowId = UuidCreator.getTimeOrderedEpoch()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            BankStatementRow.Create(
              rowId,
              sid,
              0,
              LocalDate.of(2026, 4, 1),
              BigDecimal("-312.96"),
              "SANITAS",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
          )
      )

      val req = ConfirmBankStatementRow.Request(LocalDate.of(2026, 4, 1), BigDecimal("-312.96"), "SANITAS EDITED")
      client.confirmBankStatementRow(stmtId, rowId, req).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.size must be(1)
      stmt.statementRows(0).description must be("SANITAS EDITED")
      stmt.statementRows(0).manuallyEdited must be(true)
      stmt.statementRows(0).confirmedAt must not be empty
    }

    "fail to confirm a bank statement row for another user's statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val now = Instant.now()
      val rowId = UuidCreator.getTimeOrderedEpoch()
      val stmtId = createBankStatementWithRows(
        userId,
        sid =>
          List(
            BankStatementRow.Create(
              rowId,
              sid,
              0,
              LocalDate.of(2026, 4, 1),
              BigDecimal("-100.00"),
              "Test",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
          )
      )

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val req = ConfirmBankStatementRow.Request(LocalDate.of(2026, 4, 1), BigDecimal("-100.00"), "Test")
      val error = client2.confirmBankStatementRow(stmtId, rowId, req).expectError
      error must be("Bank statement not found")
    }
  }

  private def driveReceiptToCompletion(documentId: UUID): Unit = {
    app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue
    val pollAction = app.injector.instanceOf[PollReceiptAction]
    val parseAction = app.injector.instanceOf[ParseReceiptAction]
    pollAction(documentId).futureValue
    pollAction(documentId).futureValue
    pollAction(documentId).futureValue
    parseAction(documentId).futureValue
  }

  private def setupParsedReceipt()(implicit client: net.wiringbits.api.ApiClient): UUID = {
    val uploaded = client.uploadReceipt("receipt-bytes".getBytes("UTF-8"), "receipt", "test.pdf").futureValue
    val documentId = client.getReceipt(uploaded.id).futureValue.documentId
    driveReceiptToCompletion(documentId)
    uploaded.id
  }

  "receipt expense linking" should {
    "link-existing: sets receipt.expense_id without creating a new expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue

      val receipt = client.getReceipt(receiptId).futureValue
      receipt.expenseId must be(Some(expenseId))
      client.getExpenses.futureValue.data.size must be(1)
    }

    "link-existing: does not modify the matched expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("99.99"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue

      val expense = client.getExpenses.futureValue.data.find(_.id == expenseId).get
      expense.amount must be(BigDecimal("99.99"))
    }

    "link-existing: works even when parsed amount != expense amount" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("100.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue

      client.getReceipt(receiptId).futureValue.expenseId must be(Some(expenseId))
    }

    "link-existing: does not alter a matched expense's splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(CreateCategory.Request("Groceries")).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(
          CreateExpense.Request(
            BigDecimal("50.00"),
            sampleDate,
            None,
            None,
            None,
            List(CreateExpense.Split(cat.id, BigDecimal("30.00")))
          )
        )
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue

      val expense = client.getExpenses.futureValue.data.find(_.id == expenseId).get
      expense.splits must have size 1
      expense.splits.head.amount must be(BigDecimal("30.00"))
    }

    "link-existing: rejects another user's expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val expenseId = client2
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id

      val error = client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).expectError
      error must be("Expense not found")
    }

    "link-existing: rejects another user's receipt" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).expectError
      error must be("Receipt not found")
    }

    "two-evidence convergence: receipt and row can both link to the same expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()
      val (stmtId, rowId) = setupStmtWithRow()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id

      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue

      client.getReceipt(receiptId).futureValue.expenseId must be(Some(expenseId))
      client.getBankStatement(stmtId).futureValue.statementRows.head.expenseId must be(Some(expenseId))
      client.getExpenses.futureValue.data.size must be(1)
    }

    "unlink: clears receipt.expense_id, expense remains intact" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue
      client.unlinkReceipt(receiptId).futureValue

      client.getReceipt(receiptId).futureValue.expenseId must be(None)
      client.getExpenses.futureValue.data.map(_.id) must contain(expenseId)
    }

    "unlink: rejects another user's receipt" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.unlinkReceipt(receiptId).expectError
      error must be("Receipt not found")
    }

    "delete-expense: nulls receipt.expense_id (ON DELETE SET NULL)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkReceiptToExpense(receiptId, LinkReceiptToExpense.Request(expenseId)).futureValue
      client.deleteExpense(expenseId).futureValue

      client.getReceipt(receiptId).futureValue.expenseId must be(None)
    }
  }

  private def makeRow(
      rowId: UUID,
      stmtId: UUID,
      amount: BigDecimal,
      index: Int = 0
  )(implicit now: Instant): BankStatementRow.Create =
    BankStatementRow.Create(
      rowId,
      stmtId,
      index,
      LocalDate.of(2026, 4, 1),
      amount,
      "Test row",
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

  "bank statement row expense linking" should {
    "create-from-row: creates expense and links it to the row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      val resp = client.createExpenseFromRow(stmtId, rowId, req).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.expenseId must be(Some(resp.expenseId))
    }

    "create-from-row: new expense appears in expense list" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      val resp = client.createExpenseFromRow(stmtId, rowId, req).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.map(_.id) must contain(resp.expenseId)
    }

    "create-from-row: credit row is rejected" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow(amount = BigDecimal("50.00"))

      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      val error = client.createExpenseFromRow(stmtId, rowId, req).expectError
      error must include("debit")
    }

    "create-from-row: rejects another user's statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val req = CreateExpenseFromRow.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil)
      val error = client2.createExpenseFromRow(stmtId, rowId, req).expectError
      error must be("Bank statement not found")
    }

    "link-existing: sets row.expense_id, does not modify expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.expenseId must be(Some(expenseId))

      val expenses = client.getExpenses.futureValue
      expenses.data.find(_.id == expenseId).get.amount must be(BigDecimal("50.00"))
    }

    "link-existing: credit row is rejected" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow(amount = BigDecimal("50.00"))

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      val error = client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).expectError
      error must include("debit")
    }

    "link-existing: rejects another user's expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val expenseId = client2
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id

      val error = client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).expectError
      error must be("Expense not found")
    }

    "multiple evidence: same expense can be linked from receipt AND row" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val receiptBytes = "fake-receipt-distinct".getBytes("UTF-8")
      val uploadedReceipt = client.uploadReceipt(receiptBytes, "receipt", "test.pdf").futureValue
      val documentId = client.getReceipt(uploadedReceipt.id).futureValue.documentId
      driveReceiptToCompletion(documentId)

      val confirmReq = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val receiptConfirmed = client.confirmReceipt(uploadedReceipt.id, confirmReq).futureValue
      val expenseId = receiptConfirmed.expenseId.get

      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.expenseId must be(Some(expenseId))
    }

    "unlink: clears row.expense_id, expense remains intact" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue
      client.unlinkRow(stmtId, rowId).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.expenseId must be(None)

      val expenses = client.getExpenses.futureValue
      expenses.data.map(_.id) must contain(expenseId)
    }

    "unlink: rejects another user's statement" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.unlinkRow(stmtId, rowId).expectError
      error must be("Bank statement not found")
    }

    "delete-expense: nulls row.expense_id (ON DELETE SET NULL)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val (stmtId, rowId) = setupStmtWithRow()

      val expenseId = client
        .createExpense(CreateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .futureValue
        .id
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue
      client.deleteExpense(expenseId).futureValue

      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows.head.expenseId must be(None)
    }
  }

  "confirm-time rule creation" should {
    "creates a set_merchant rule when parsedMerchantName is confirmed with a merchant" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        val receiptId = setupParsedReceipt()
        val merchantId = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue.id

        val req = ConfirmReceipt.Request(
          Some(BigDecimal("50.00")),
          Some(sampleDate),
          None,
          Some(merchantId),
          None,
          Nil,
          Some("AMZN")
        )
        client.confirmReceipt(receiptId, req).futureValue

        val rules = client.getImportRules.futureValue.data
        val rule = rules.find(r => r.pattern == "AMZN" && r.merchantId.contains(merchantId))
        rule must not be empty
        rule.get.autoCreated must be(true)
    }

    "is idempotent when rule already exists for the same pattern and merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId1 = setupParsedReceipt()
      val receiptId2 =
        client.uploadReceipt("second-receipt-idem".getBytes("UTF-8"), "receipt", "r2.pdf").futureValue.id
      val docId2 = client.getReceipt(receiptId2).futureValue.documentId
      driveReceiptToCompletion(docId2)
      val merchantId = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue.id

      val req = ConfirmReceipt.Request(
        Some(BigDecimal("50.00")),
        Some(sampleDate),
        None,
        Some(merchantId),
        None,
        Nil,
        Some("AMZN")
      )
      client.confirmReceipt(receiptId1, req).futureValue
      client
        .confirmReceipt(receiptId2, req.copy(amount = Some(BigDecimal("75.00"))))
        .futureValue

      val rules = client.getImportRules.futureValue.data.filter(r => r.pattern == "AMZN")
      rules must have size 1
    }

    "skips rule creation when parsedMerchantName is None" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()
      val merchantId = client.createMerchant(CreateMerchant.Request("Amazon", None, None)).futureValue.id

      val req =
        ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, Some(merchantId), None, Nil, None)
      client.confirmReceipt(receiptId, req).futureValue

      client.getImportRules.futureValue.data must be(empty)
    }

    "skips rule creation when merchantId is None" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = setupParsedReceipt()

      val req =
        ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil, Some("AMZN"))
      client.confirmReceipt(receiptId, req).futureValue

      client.getImportRules.futureValue.data must be(empty)
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getReceipts.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }
}
