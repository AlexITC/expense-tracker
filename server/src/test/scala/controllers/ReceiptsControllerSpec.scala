package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import controllers.common.PlayPostgresSpec
import net.wiringbits.actions.documents.{ParseReceiptAction, PollReceiptAction, ProcessDocumentAction}
import net.wiringbits.api.models.documents.{DocumentStatus => ApiDocumentStatus}
import net.wiringbits.api.models.receipts.ConfirmReceipt
import net.wiringbits.apis.{EmailApi, MockTextractClient, ReCaptchaApi, TextractApiType, TextractClient}
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.common.models.Captcha
import net.wiringbits.models.documents.{DocumentStatus => ServerDocumentStatus}
import net.wiringbits.repositories.models.TextractCache
import net.wiringbits.repositories.{
  DocumentsRepository,
  ReceiptRepository,
  TextractCacheRepository,
  TextractJobRepository
}
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

class ReceiptsControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  private val sampleBytes: Array[Byte] = "fake-receipt-content-1".getBytes("UTF-8")
  private val sampleBytes2: Array[Byte] = "fake-receipt-content-2".getBytes("UTF-8")
  private val sampleDate = LocalDate.of(2024, 1, 15)

  /** Drives a receipt document through the full async Textract lifecycle. MockTextractClient uses inProgressPolls=2, so
    * 3 poll calls clear IN_PROGRESS and reach SUCCEEDED.
    */
  private def driveReceiptToCompletion(documentId: UUID): Unit = {
    app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue
    val pollAction = app.injector.instanceOf[PollReceiptAction]
    val parseAction = app.injector.instanceOf[ParseReceiptAction]
    pollAction(documentId).futureValue
    pollAction(documentId).futureValue
    pollAction(documentId).futureValue
    parseAction(documentId).futureValue
  }

  private def setReceiptParsed(
      receiptId: UUID,
      userId: UUID,
      amount: BigDecimal = BigDecimal("50.00"),
      merchantName: Option[String] = None
  ): Unit = {
    val receiptRepo = app.injector.instanceOf[ReceiptRepository]
    receiptRepo.updateStatus(receiptId, userId, ServerDocumentStatus.Parsed).futureValue
    receiptRepo.updateParsed(receiptId, Some(amount), Some(sampleDate), None, merchantName, None, None).futureValue
  }

  private def buildClient() = {
    import sttp.client3.HttpClientFutureBackend
    import net.wiringbits.api.ApiClient
    implicit val sttpBackend: sttp.client3.SttpBackend[scala.concurrent.Future, ?] = HttpClientFutureBackend()
    new ApiClient(ApiClient.Config(s"http://localhost:$port"))
  }

  // ── Async lifecycle ──────────────────────────────────────────────────────────

  "Receipt async Textract lifecycle" should {
    "upload starts job; in-progress polls re-enqueue; succeeded assembles cache; parser runs to Parsed" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
        val documentId = client.getReceipt(receiptId).futureValue.documentId

        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Ingested)

        // processAction: Processing, starts expense analysis, enqueues PollReceipt
        app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Processing)

        val jobRepo = app.injector.instanceOf[TextractJobRepository]
        val job = jobRepo.findByDocumentId(documentId).futureValue
        job must not be empty
        job.get.apiType must be(TextractApiType.AnalyzeExpense)

        val pollAction = app.injector.instanceOf[PollReceiptAction]
        pollAction(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Processing)
        pollAction(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Processing)

        // Third poll: SUCCEEDED → cache written, status → Analyzed
        pollAction(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Analyzed)

        val docRepo = app.injector.instanceOf[DocumentsRepository]
        val doc = docRepo.findById(documentId).futureValue.get
        val cacheRepo = app.injector.instanceOf[TextractCacheRepository]
        cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense).futureValue must not be empty

        // ParseReceipt: reads cache → Parsed
        app.injector.instanceOf[ParseReceiptAction].apply(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Parsed)
    }

    "cache hit before start: pre-existing cache skips new Textract job, ParseReceipt runs directly" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
        val documentId = client.getReceipt(receiptId).futureValue.documentId
        val docRepo = app.injector.instanceOf[DocumentsRepository]
        val sha256 = docRepo.findById(documentId).futureValue.get.sha256

        val cacheRepo = app.injector.instanceOf[TextractCacheRepository]
        cacheRepo
          .create(
            TextractCache
              .Create(sha256, TextractApiType.AnalyzeExpense, MockTextractClient.cannedExpenseResponse, Instant.now())
          )
          .futureValue

        // processAction should hit cache → set Analyzed, enqueue ParseReceipt; no TextractJob created
        app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue

        val jobRepo = app.injector.instanceOf[TextractJobRepository]
        jobRepo.findByDocumentId(documentId).futureValue must be(None)

        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Analyzed)

        app.injector.instanceOf[ParseReceiptAction].apply(documentId).futureValue
        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Parsed)
    }

    "job FAILED → document marked Unsupported, nothing cached" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "fail.jpg").futureValue.id
      val documentId = client.getReceipt(receiptId).futureValue.documentId

      app.injector.instanceOf[ProcessDocumentAction].apply(documentId).futureValue

      val jobRepo = app.injector.instanceOf[TextractJobRepository]
      val job = jobRepo.findByDocumentId(documentId).futureValue.get
      val mockClient = app.injector.instanceOf[TextractClient].asInstanceOf[MockTextractClient]
      mockClient.failNextExpensePoll(job.jobId.get)

      app.injector.instanceOf[PollReceiptAction].apply(documentId).futureValue

      client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Unsupported)

      val docRepo = app.injector.instanceOf[DocumentsRepository]
      val doc = docRepo.findById(documentId).futureValue.get
      val cacheRepo = app.injector.instanceOf[TextractCacheRepository]
      cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense).futureValue must be(None)
    }

    "api_type constant 'analyze_expense_async' is used for receipt jobs" in {
      TextractApiType.AnalyzeExpense must be("analyze_expense_async")
      TextractApiType.AnalyzeDocument must be("analyze_document_async")
    }

    "full lifecycle end-to-end via mock: Ingested → Processing → Analyzed → Parsed" in withApiClient {
      implicit client =>
        createVerifyLoginUser(tokenGenerator).futureValue
        val receiptId = client.uploadReceipt(sampleBytes2, "receipt", "full.jpg").futureValue.id
        val documentId = client.getReceipt(receiptId).futureValue.documentId

        driveReceiptToCompletion(documentId)

        client.getReceipt(receiptId).futureValue.documentStatus must be(ApiDocumentStatus.Parsed)
    }

    "user isolation: receipt processing is scoped to the owner" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      client2.getReceipts.futureValue.data must be(empty)
    }
  }

  // ── POST /receipts (upload) ──────────────────────────────────────────────────

  "POST /receipts (upload)" should {
    "upload a receipt successfully" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val resp = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue
      resp.id must not be null
    }

    "uploading two different files creates two receipts" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.uploadReceipt(sampleBytes, "receipt", "file1.jpg").futureValue
      client.uploadReceipt(sampleBytes2, "receipt", "file2.jpg").futureValue

      val receipts = client.getReceipts.futureValue
      receipts.data.length must be(2)
    }

    "list items report the receipt status as documentStatus" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
      client.getReceipts.futureValue.data.find(_.id == receiptId).get.documentStatus must be(
        ApiDocumentStatus.Ingested
      )

      app.injector
        .instanceOf[ReceiptRepository]
        .updateStatus(receiptId, loginResp.id, ServerDocumentStatus.ParseFailed)
        .futureValue

      client.getReceipts.futureValue.data.find(_.id == receiptId).get.documentStatus must be(
        ApiDocumentStatus.ParseFailed
      )
    }

    "duplicate sha256 returns the existing receipt id (not a new one)" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val first = client.uploadReceipt(sampleBytes, "receipt", "file.jpg").futureValue
      val second = client.uploadReceipt(sampleBytes, "receipt", "file_again.jpg").futureValue

      second.id must be(first.id)

      val receipts = client.getReceipts.futureValue
      receipts.data.length must be(1)
    }

    "uploading three files sequentially succeeds even if one is a duplicate" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val r1 = client.uploadReceipt(sampleBytes, "receipt", "file1.jpg").futureValue
      val r2 = client.uploadReceipt(sampleBytes2, "receipt", "file2.jpg").futureValue
      val r3 = client.uploadReceipt(sampleBytes, "receipt", "file1_dup.jpg").futureValue

      r3.id must be(r1.id)
      r1.id must not be r2.id

      val receipts = client.getReceipts.futureValue
      receipts.data.length must be(2)
    }

    "user isolation: receipts are not visible to other users" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.uploadReceipt(sampleBytes, "receipt", "file.jpg").futureValue

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      client2.getReceipts.futureValue.data must be(empty)
    }
  }

  // ── POST /receipts/:id/confirm ───────────────────────────────────────────────

  "POST /receipts/:id/confirm" should {
    "confirm a parsed receipt creates an expense and links receipt.expenseId" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
      setReceiptParsed(receiptId, loginResp.id)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val resp = client.confirmReceipt(receiptId, req).futureValue
      resp.expenseId must not be None

      val receipts = client.getReceipts.futureValue
      receipts.data.find(_.id == receiptId).get.expenseId must not be None
    }

    "confirm two receipts sequentially creates two expenses" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val id1 = client.uploadReceipt(sampleBytes, "receipt", "r1.jpg").futureValue.id
      val id2 = client.uploadReceipt(sampleBytes2, "receipt", "r2.jpg").futureValue.id
      setReceiptParsed(id1, loginResp.id, BigDecimal("50.00"))
      setReceiptParsed(id2, loginResp.id, BigDecimal("75.00"))

      val req1 = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val req2 = ConfirmReceipt.Request(Some(BigDecimal("75.00")), Some(sampleDate), None, None, None, Nil)
      client.confirmReceipt(id1, req1).futureValue
      client.confirmReceipt(id2, req2).futureValue

      val receipts = client.getReceipts.futureValue
      receipts.data.find(_.id == id1).get.expenseId must not be None
      receipts.data.find(_.id == id2).get.expenseId must not be None

      val expenses = client.getExpenses.futureValue
      expenses.data.length must be(2)
    }

    "fail when receipt is not in Parsed status" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Receipt is not in Parsed status")
    }

    "fail when receipt is already confirmed" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
      setReceiptParsed(receiptId, loginResp.id)

      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      client.confirmReceipt(receiptId, req).futureValue

      val error = client.confirmReceipt(receiptId, req).expectError
      error must be("Receipt is already linked to an expense — unlink first")
    }

    "user isolation: cannot confirm another user's receipt" in withApiClient { implicit client =>
      val loginResp = createVerifyLoginUser(tokenGenerator).futureValue
      val receiptId = client.uploadReceipt(sampleBytes, "receipt", "test.jpg").futureValue.id
      setReceiptParsed(receiptId, loginResp.id)

      val client2 = buildClient()
      createVerifyLoginUser(tokenGenerator)(using executionContext, client2).futureValue
      val req = ConfirmReceipt.Request(Some(BigDecimal("50.00")), Some(sampleDate), None, None, None, Nil)
      val error = client2.confirmReceipt(receiptId, req).expectError
      error must be("Receipt not found")
    }
  }

  "Unauthenticated access" should {
    "reject requests without a valid session" in withApiClient { client =>
      val error = client.getReceipts.expectError
      error must be("Unauthorized: Invalid or missing authentication")
    }
  }
}
