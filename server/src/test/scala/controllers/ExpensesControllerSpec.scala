package controllers

import net.wiringbits.common.ExpenseSplits
import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.ApiClient
import net.wiringbits.api.models.bankstatements.LinkRowToExpense
import net.wiringbits.api.models.expenses.{CreateExpense, MergeDetailsSource, MergeExpenses, UpdateExpense}
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.ReceiptRepository
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatementRow, Document, Receipt}
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import utils.{ExpenseUtils, LoginUtils}

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

class ExpensesControllerSpec extends PlayPostgresSpec with LoginUtils with ExpenseUtils with MockitoSugar {

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
        inject.bind[TokenGenerator].to(tokenGenerator)
      )

  private val sampleDate = LocalDate.of(2024, 1, 15)

  private def createRequest(
      amount: BigDecimal = BigDecimal("50.00"),
      date: LocalDate = sampleDate,
      splits: Option[List[CreateExpense.Split]] = None
  )(using client: ApiClient) = CreateExpense.Request(
    amount = amount,
    date = date,
    time = None,
    merchantId = None,
    notes = None,
    splits = splits.getOrElse(fullSplit(amount).futureValue)
  )

  "GET /expenses" should {
    "return an empty list when user has no expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val response = client.getExpenses.futureValue
      response.data must be(empty)
    }

    "return the user's expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      client.createExpense(createRequest(BigDecimal("10.00"))).futureValue
      client.createExpense(createRequest(BigDecimal("20.00"))).futureValue

      val response = client.getExpenses.futureValue
      response.data.length must be(2)
    }

    "not return another user's expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue

      client2.getExpenses.futureValue.data must be(empty)
    }

    "return only expenses within the requested date range" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 9))).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 10))).futureValue
      client.createExpense(createRequest(date = LocalDate.of(2024, 1, 21))).futureValue

      val response = client
        .getExpensesFiltered(Some(LocalDate.of(2024, 1, 10)), Some(LocalDate.of(2024, 1, 20)), unlinkedOnly = false)
        .futureValue
      response.data.map(_.date) must be(List(LocalDate.of(2024, 1, 10)))
    }

    "omit row-linked expenses only when unlinkedOnly is set" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val linkedId = client.createExpense(createRequest()).futureValue.id
      val unlinkedId = client.createExpense(createRequest()).futureValue.id
      val userId = client.currentUser.futureValue.id
      val rowId = UUID.randomUUID()
      val now = Instant.now()
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
              "Test row",
              manuallyEdited = false,
              now,
              now,
              txnType = "purchase"
            )
          )
      )
      client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(linkedId)).futureValue

      client.getExpensesFiltered(None, None, unlinkedOnly = true).futureValue.data.map(_.id) must be(
        List(unlinkedId)
      )
      client.getExpensesFiltered(None, None, unlinkedOnly = false).futureValue.data.map(_.id).toSet must be(
        Set(linkedId, unlinkedId)
      )
    }
  }

  "POST /expenses" should {
    "fail when there are no splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createExpense(createRequest(BigDecimal("75.00"), splits = Some(Nil))).expectError
      error must be(ExpenseSplits.NotFullyAllocatedError)
    }

    "create an expense with splits summing exactly to total" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val splits = List(CreateExpense.Split(cat.id, BigDecimal("50.00")))
      client.createExpense(createRequest(BigDecimal("50.00"), splits = Some(splits))).futureValue
    }

    "create an expense with a merchant" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant =
        client
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Amazon", None, None))
          .futureValue

      val req = CreateExpense.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        Some(merchant.id),
        None,
        fullSplit(BigDecimal("50.00")).futureValue
      )
      val response = client.createExpense(req).futureValue
      val expenses = client.getExpenses.futureValue
      expenses.data.head.merchantName must be(Some("Amazon"))
    }

    "fail when amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createExpense(createRequest(BigDecimal("0"))).expectError
      error must be("Amount must be greater than 0")
    }

    "fail when notes exceed 255 characters" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val req = CreateExpense.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        None,
        Some("a" * 256),
        fullSplit(BigDecimal("50.00")).futureValue
      )
      val error = client.createExpense(req).expectError
      error must be("Notes must be 255 characters or less")
    }

    "fail when amount is negative" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.createExpense(createRequest(BigDecimal("-1"))).expectError
      error must be("Amount must be greater than 0")
    }

    "fail when split amount is zero" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val error = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = Some(List(CreateExpense.Split(cat.id, BigDecimal("0")))))
        )
        .expectError
      error must be("Split amount must be greater than 0")
    }

    "fail when duplicate category in splits" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val splits =
        List(CreateExpense.Split(cat.id, BigDecimal("20.00")), CreateExpense.Split(cat.id, BigDecimal("10.00")))
      val error = client.createExpense(createRequest(BigDecimal("50.00"), splits = Some(splits))).expectError
      error must be("Duplicate category in splits")
    }

    "fail when merchant belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val merchant =
        client2
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Shop", None, None))
          .futureValue

      val req = CreateExpense.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        Some(merchant.id),
        None,
        fullSplit(BigDecimal("50.00")).futureValue
      )
      val error = client.createExpense(req).expectError
      error must be("Merchant not found")
    }

    "fail when category belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val cat = client2.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue

      val error = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = Some(List(CreateExpense.Split(cat.id, BigDecimal("50.00")))))
        )
        .expectError
      error must be("Category not found")
    }
  }

  "PUT /expenses/:id" should {
    "update all fields" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest(BigDecimal("50.00"))).futureValue

      val newDate = LocalDate.of(2024, 6, 1)
      client
        .updateExpense(
          created.id,
          UpdateExpense
            .Request(BigDecimal("99.00"), newDate, None, None, Some("lunch"), fullUpdateSplit(BigDecimal("99.00")))
        )
        .futureValue
      val expenses = client.getExpenses.futureValue
      val updated = expenses.data.find(_.id == created.id).get
      updated.amount must be(BigDecimal("99.00"))
      updated.date must be(newDate)
      updated.notes must be(Some("lunch"))
    }

    "replace split set on update" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat1 = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      val cat2 =
        client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Transport")).futureValue
      val created = client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = Some(List(CreateExpense.Split(cat1.id, BigDecimal("50.00")))))
        )
        .futureValue

      client
        .updateExpense(
          created.id,
          UpdateExpense.Request(
            BigDecimal("50.00"),
            sampleDate,
            None,
            None,
            None,
            List(UpdateExpense.Split(cat2.id, BigDecimal("50.00")))
          )
        )
        .futureValue

      val expenses = client.getExpenses.futureValue
      val updated = expenses.data.find(_.id == created.id).get
      updated.splits.length must be(1)
      updated.splits.head.categoryId must be(cat2.id)
    }

    "fail when clearing splits with an empty list" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest(BigDecimal("50.00"))).futureValue

      val error = client
        .updateExpense(created.id, UpdateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .expectError
      error must be(ExpenseSplits.NotFullyAllocatedError)
    }

    "fail when expense belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2
        .updateExpense(created.id, UpdateExpense.Request(BigDecimal("50.00"), sampleDate, None, None, None, Nil))
        .expectError
      error must be("Expense not found")
    }
  }

  "DELETE /expenses/:id" should {
    "delete an expense" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      client.deleteExpense(created.id).futureValue
      client.getExpenses.futureValue.data must be(empty)
    }

    "fail when expense belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val created = client.createExpense(createRequest()).futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val error = client2.deleteExpense(created.id).expectError
      error must be("Expense not found")
    }

    "fail when expense does not exist" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue

      val error = client.deleteExpense(UUID.randomUUID()).expectError
      error must be("Expense not found")
    }
  }

  "category delete" should {
    "remove related splits but leave expense intact" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val cat = client.createCategory(net.wiringbits.api.models.categories.CreateCategory.Request("Food")).futureValue
      client
        .createExpense(
          createRequest(BigDecimal("50.00"), splits = Some(List(CreateExpense.Split(cat.id, BigDecimal("50.00")))))
        )
        .futureValue

      client.deleteCategory(cat.id).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.length must be(1)
      expenses.data.head.splits must be(empty)
    }
  }

  "merchant delete" should {
    "set merchant_id to null on referencing expenses" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val merchant =
        client
          .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Amazon", None, None))
          .futureValue
      val req = CreateExpense.Request(
        BigDecimal("50.00"),
        sampleDate,
        None,
        Some(merchant.id),
        None,
        fullSplit(BigDecimal("50.00")).futureValue
      )
      client.createExpense(req).futureValue

      client.deleteMerchant(merchant.id).futureValue

      val expenses = client.getExpenses.futureValue
      expenses.data.head.merchantId must be(None)
      expenses.data.head.merchantName must be(None)
    }
  }

  "POST /expenses/:id/merge" should {
    "move the receipt to the row-linked expense and delete the duplicate" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val survivorId = client.createExpense(createRequest()).futureValue.id
      val loserId = client.createExpense(createRequest()).futureValue.id
      val rowId = linkToNewRow(userId, survivorId)
      val receiptId = createLinkedReceipt(userId, loserId)

      client
        .mergeExpenses(survivorId, MergeExpenses.Request(loserId = loserId, keepDetailsFrom = MergeDetailsSource.Row))
        .futureValue

      val expenses = client.getExpenses.futureValue.data
      expenses.map(_.id) must be(List(survivorId))
      expenses.head.receiptIds must be(List(receiptId))
      expenses.head.rowIds must be(List(rowId))
    }

    "keep the receipt's merchant, notes and splits when requested" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val merchant = client
        .createMerchant(net.wiringbits.api.models.merchants.CreateMerchant.Request("Receipt shop", None, None))
        .futureValue
      val loserSplits = fullSplit(BigDecimal("50.00")).futureValue
      val survivorId = client.createExpense(createRequest()).futureValue.id
      val loserId = client
        .createExpense(
          createRequest().copy(merchantId = Some(merchant.id), notes = Some("receipt"), splits = loserSplits)
        )
        .futureValue
        .id
      linkToNewRow(userId = userId, expenseId = survivorId)
      createLinkedReceipt(userId = userId, expenseId = loserId)

      client
        .mergeExpenses(
          survivorId,
          MergeExpenses.Request(loserId = loserId, keepDetailsFrom = MergeDetailsSource.Receipt)
        )
        .futureValue

      val survivor = client.getExpenses.futureValue.data.find(_.id == survivorId).value
      survivor.merchantId must be(Some(merchant.id))
      survivor.notes must be(Some("receipt"))
      survivor.splits.map(s => s.categoryId -> s.amount) must be(loserSplits.map(s => s.categoryId -> s.amount))
    }

    "fail when the survivor belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val survivorId = client.createExpense(createRequest()).futureValue.id
      linkToNewRow(userId, survivorId)

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val userId2 = client2.currentUser.futureValue.id
      val loserId = client2.createExpense(createRequest()(using client2)).futureValue.id
      createLinkedReceipt(userId2, loserId)

      val error = client2
        .mergeExpenses(survivorId, MergeExpenses.Request(loserId = loserId, keepDetailsFrom = MergeDetailsSource.Row))
        .expectError
      error must include("Expense not found")
      client2.getExpenses.futureValue.data.map(_.id) must be(List(loserId))
    }

    "fail when the loser belongs to another user" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val loserId = client.createExpense(createRequest()).futureValue.id
      createLinkedReceipt(userId, loserId)

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val userId2 = client2.currentUser.futureValue.id
      val survivorId = client2.createExpense(createRequest()(using client2)).futureValue.id
      linkToNewRow(userId2, survivorId)(using client2)

      val error = client2
        .mergeExpenses(survivorId, MergeExpenses.Request(loserId = loserId, keepDetailsFrom = MergeDetailsSource.Row))
        .expectError
      error must include("Expense not found")
      client.getExpenses.futureValue.data.map(_.id) must be(List(loserId))
    }

    "fail when the row-linked expense is passed as the loser" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val userId = client.currentUser.futureValue.id
      val rowLinkedId = client.createExpense(createRequest()).futureValue.id
      val receiptLinkedId = client.createExpense(createRequest()).futureValue.id
      linkToNewRow(userId, rowLinkedId)
      createLinkedReceipt(userId, receiptLinkedId)

      val error = client
        .mergeExpenses(
          receiptLinkedId,
          MergeExpenses.Request(loserId = rowLinkedId, keepDetailsFrom = MergeDetailsSource.Row)
        )
        .expectError
      error must include("The surviving expense must be linked to a bank statement row")
      client.getExpenses.futureValue.data.map(_.id).toSet must be(Set(rowLinkedId, receiptLinkedId))
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getExpenses.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }

  private def fullUpdateSplit(amount: BigDecimal)(using client: ApiClient): List[UpdateExpense.Split] =
    fullSplit(amount).futureValue.map(s => UpdateExpense.Split(s.categoryId, s.amount))

  private def linkToNewRow(userId: UUID, expenseId: UUID)(using client: ApiClient): UUID = {
    val rowId = UUID.randomUUID()
    val now = Instant.now()
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
            "Test row",
            manuallyEdited = false,
            now,
            now,
            txnType = "purchase"
          )
        )
    )
    client.linkRowToExpense(stmtId, rowId, LinkRowToExpense.Request(expenseId)).futureValue
    rowId
  }

  /** Creates a receipt directly (no upload / Textract) and links it to the expense. */
  private def createLinkedReceipt(userId: UUID, expenseId: UUID): UUID = {
    val repo = app.injector.instanceOf[ReceiptRepository]
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
    val receipt = Receipt.Create(
      id = UUID.randomUUID(),
      userId = userId,
      documentId = docId,
      manuallyEdited = false,
      status = DocumentStatus.Ingested,
      createdAt = now,
      updatedAt = now
    )
    // Scheduled far in the future so the live executor never processes the document while a test runs.
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = BackgroundJobPayload.ProcessDocument(docId),
      status = BackgroundJobStatus.Pending,
      executeAt = now.plus(Duration.ofDays(365)),
      createdAt = now,
      updatedAt = now
    )
    repo.createWithDocument(document, receipt, job).futureValue
    repo.linkToExpense(receipt.id, userId, expenseId).futureValue
    receipt.id
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }
}
