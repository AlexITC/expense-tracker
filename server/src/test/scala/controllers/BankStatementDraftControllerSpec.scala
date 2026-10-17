package controllers

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.{BankStatementDraft, ExtractionInfo}
import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.actions.bankstatements.ExtractBankStatementDraftAction
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.repositories.daos.BankStatementDraftDAO
import net.wiringbits.repositories.models.{BankStatementDraft => DraftRecord}
import net.wiringbits.services.AiStatementExtractor.{ExtractionResult, Meta, Txn}
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage, StatementExtractionService}
import net.wiringbits.util.TokenGenerator
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.db.Database
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import utils.LoginUtils

import java.io.ByteArrayOutputStream
import java.time.{Clock, Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

class BankStatementDraftControllerSpec extends PlayPostgresSpec with LoginUtils with MockitoSugar {

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

  private def reconcilingPayload: BankStatementDraft.Payload =
    BankStatementDraft.Payload(
      metadata = BankStatementDraft.Metadata(
        bankName = Some("BBVA"),
        productType = Some("debit"),
        currency = Some("EUR"),
        accountLast4 = Some("1234"),
        periodStart = Some(LocalDate.of(2026, 1, 1)),
        periodEnd = Some(LocalDate.of(2026, 1, 31)),
        openingBalance = Some(BigDecimal("100.00")),
        closingBalance = Some(BigDecimal("60.00"))
      ),
      transactions = List(
        BankStatementDraft.Transaction(
          date = LocalDate.of(2026, 1, 15),
          amount = BigDecimal("-40.00"),
          description = "MOCK PURCHASE",
          txnType = Some("purchase"),
          runningBalance = Some(BigDecimal("60.00"))
        )
      ),
      reconciled = true,
      discrepancy = None,
      model = "test",
      stage = Some(ExtractionSource.BdaMarkdown)
    )

  // Seed a ready draft directly (no job) and return its id, so the background executor doesn't race the test.
  private def seedReadyDraft(userId: UUID, payload: BankStatementDraft.Payload): UUID =
    seedReadyDraftJson(userId, Json.toJson(payload).toString)

  private def seedReadyDraftJson(userId: UUID, payloadJson: String): UUID = {
    val db = app.injector.instanceOf[Database]
    val now = Instant.now()
    val id = UuidCreator.getTimeOrderedEpoch()
    db.withConnection { implicit conn =>
      BankStatementDraftDAO.create(
        DraftRecord.Create(id, userId, "sha-" + UUID.randomUUID().toString, "stmt.pdf", "key", now, now)
      )
      BankStatementDraftDAO.markReady(id, payloadJson, now)
    }
    id
  }

  // A PDF with enough native text to pass PdfTextExtractor's usable-text check.
  private def textPdfBytes: Array[Byte] =
    Using.resource(new PDDocument()) { doc =>
      val page = new PDPage()
      doc.addPage(page)
      Using.resource(new PDPageContentStream(doc, page)) { content =>
        content.beginText()
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10)
        content.newLineAtOffset(50, 700)
        content.setLeading(14)
        (1 to 5).foreach { _ =>
          content.showText("15.01.26 MOCK PURCHASE MADRID -40.00 60.00")
          content.newLine()
        }
        content.endText()
      }
      val out = new ByteArrayOutputStream()
      doc.save(out)
      out.toByteArray
    }

  // Run the extraction job on a fresh processing draft with a stubbed ladder, so the payload comes from toPayload.
  private def extractDraft(userId: UUID, result: ExtractionResult): UUID = {
    val db = app.injector.instanceOf[Database]
    val now = Instant.now()
    val id = UuidCreator.getTimeOrderedEpoch()
    db.withConnection { implicit conn =>
      BankStatementDraftDAO.create(
        DraftRecord.Create(id, userId, "sha-" + UUID.randomUUID().toString, "stmt.pdf", "key", now, now)
      )
    }
    val bytes = textPdfBytes
    val blobStorage = new NoOpBlobStorage() {
      override def get(storageKey: String): Future[Option[Array[Byte]]] = Future.successful(Some(bytes))
    }
    val extractionService = mock[StatementExtractionService]
    when(extractionService.extract(anyString(), anyString(), anyString(), anyString()))
      .thenReturn(Future.successful(result))
    val action = new ExtractBankStatementDraftAction(
      app.injector.instanceOf[BankStatementDraftRepository],
      blobStorage,
      extractionService
    )(using app.injector.instanceOf[ExecutionContext], app.injector.instanceOf[Clock])
    action(id).futureValue
    id
  }

  private def extractionResult(source: Option[ExtractionSource]): ExtractionResult =
    ExtractionResult(
      reconciled = true,
      discrepancy = None,
      model = "test",
      transactions = List(
        Txn(LocalDate.of(2026, 1, 15), BigDecimal("-40.00"), "MOCK PURCHASE", Some(BigDecimal("60.00")), None)
      ),
      metadata = Meta(None, None, Some("EUR"), None, None, None, Some(BigDecimal("100.00")), Some(BigDecimal("60.00"))),
      source = source
    )

  "GET /bank-statements/drafts" should {
    "return an empty queue when there are no drafts" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.listBankStatementDrafts.futureValue.drafts must be(empty)
    }

    "list a ready draft and fetch it with its payload" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      val list = client.listBankStatementDrafts.futureValue.drafts
      list must have size 1
      list.head.status must be("ready")

      val draft = client.getBankStatementDraft(id).futureValue
      draft.status must be("ready")
      draft.payload.value.transactions must have size 1
    }
  }

  "GET /bank-statements/drafts/:id" should {
    "return the extraction stage stored in the payload" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      client.getBankStatementDraft(id).futureValue.payload.value.stage must be(Some(ExtractionSource.BdaMarkdown))
    }

    "read a payload stored without a stage key as no stage" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val legacyJson = Json.toJson(reconcilingPayload).as[JsObject] - "stage"
      val id = seedReadyDraftJson(userId, legacyJson.toString)

      val draft = client.getBankStatementDraft(id).futureValue
      draft.status must be("ready")
      draft.payload.value.stage must be(None)
    }

    "return the stage the extraction ladder tagged on its result" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = extractDraft(userId, extractionResult(Some(ExtractionSource.PdfText)))

      val draft = client.getBankStatementDraft(id).futureValue
      draft.status must be("ready")
      draft.payload.value.stage must be(Some(ExtractionSource.PdfText))
    }

    "return no stage when the extraction ladder left the result untagged" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = extractDraft(userId, extractionResult(None))

      val draft = client.getBankStatementDraft(id).futureValue
      draft.status must be("ready")
      draft.payload.value.stage must be(None)
    }
  }

  "POST /bank-statements/drafts" should {
    "create a processing draft and return immediately" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val data = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      data.status must be("processing")
      data.payload must be(None)
    }

    "reject the same file already in the queue" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      client.createBankStatementDraft(sampleBytes, "stmt.pdf").failed.futureValue.getMessage must include("already")
    }
  }

  "POST /bank-statements/drafts/:id/confirm" should {
    "create a statement with its rows and remove the draft" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      val stmtId = client.confirmBankStatementDraft(id).futureValue.bankStatementId
      val stmt = client.getBankStatement(stmtId).futureValue
      stmt.statementRows must have size 1
      stmt.statementRows.head.amount must be(BigDecimal("-40.00"))
      stmt.statementRows.head.txnType must be("purchase")
      stmt.bankName must be("BBVA")
      stmt.currency must be("EUR")
      stmt.periodStart must be(LocalDate.of(2026, 1, 1))

      client.listBankStatementDrafts.futureValue.drafts must be(empty)
    }

    "reject a draft that does not reconcile" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val broken = reconcilingPayload.copy(
        metadata = reconcilingPayload.metadata.copy(closingBalance = Some(BigDecimal("999.00"))),
        transactions = List(reconcilingPayload.transactions.head.copy(runningBalance = None))
      )
      val id = seedReadyDraft(userId, broken)

      client.confirmBankStatementDraft(id).failed.futureValue.getMessage must include("reconcile")
      client.listBankStatementDrafts.futureValue.drafts must not be empty
    }

    "reject a draft with missing required metadata" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val noBank = reconcilingPayload.copy(metadata = reconcilingPayload.metadata.copy(bankName = None))
      val id = seedReadyDraft(userId, noBank)

      client.confirmBankStatementDraft(id).failed.futureValue.getMessage must include("bank name")
    }

    "store the draft's extraction info on the statement" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload.copy(model = "model-y", discrepancy = Some("note")))

      val stmtId = client.confirmBankStatementDraft(id).futureValue.bankStatementId
      client.getBankStatement(stmtId).futureValue.extractionInfo must be(
        Some(ExtractionInfo(ExtractionSource.BdaMarkdown, "model-y", reconciled = true, Some("note")))
      )
    }

    "store no extraction info when the draft has no stage" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload.copy(stage = None))

      val stmtId = client.confirmBankStatementDraft(id).futureValue.bankStatementId
      client.getBankStatement(stmtId).futureValue.extractionInfo must be(None)
    }
  }

  "PUT /bank-statements/drafts/:id" should {
    "recompute reconciliation from the edited payload" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      val edited = reconcilingPayload.copy(
        transactions =
          List(reconcilingPayload.transactions.head.copy(amount = BigDecimal("-10.00"), runningBalance = None))
      )
      val updated = client.updateBankStatementDraft(id, BankStatementDraft.Update.Request(edited)).futureValue
      updated.payload.value.reconciled must be(false)
      updated.payload.value.discrepancy must not be None
    }
  }

  "DELETE /bank-statements/drafts/:id" should {
    "discard the draft" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      client.discardBankStatementDraft(id).futureValue.discarded must be(true)
      client.listBankStatementDrafts.futureValue.drafts must be(empty)
    }
  }
}
