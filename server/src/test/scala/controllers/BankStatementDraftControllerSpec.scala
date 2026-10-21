package controllers

import anorm.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.github.f4b6a3.uuid.UuidCreator
import controllers.common.PlayPostgresSpec
import net.wiringbits.api.models.bankstatements.{BankStatementDraft, ExtractionInfo}
import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.apis.models.EmailRequest
import net.wiringbits.apis.{EmailApi, ReCaptchaApi}
import net.wiringbits.common.models.Captcha
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.BackgroundJobType
import net.wiringbits.repositories.daos.{BankStatementDraftDAO, DocumentDAO}
import net.wiringbits.repositories.models.{BankStatementDraft => DraftRecord, Document}
import net.wiringbits.services.{BlobStorage, NoOpBlobStorage}
import net.wiringbits.util.TokenGenerator
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatestplus.mockito.MockitoSugar
import play.api.db.Database
import play.api.inject
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import utils.LoginUtils

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.Future

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
    val document = Document.Create(
      id = UuidCreator.getTimeOrderedEpoch(),
      userId = userId,
      sha256 = "sha-" + UUID.randomUUID().toString,
      storageKey = "key",
      kind = DocumentKind.BankStatementPdf,
      filename = "stmt.pdf",
      status = DocumentStatus.Ingested,
      createdAt = now,
      updatedAt = now
    )
    db.withConnection { implicit conn =>
      DocumentDAO.create(document)
      BankStatementDraftDAO.create(
        DraftRecord.Create(id, userId, document.id, document.sha256, "stmt.pdf", "key", now, now)
      )
      BankStatementDraftDAO.markReady(id, payloadJson, now)
    }
    id
  }

  private def storedDocuments(userId: UUID): List[Document] =
    app.injector.instanceOf[Database].withConnection { implicit conn => DocumentDAO.all(userId) }

  private def storedDraftDocumentId(draftId: UUID, userId: UUID): UUID =
    app.injector.instanceOf[Database].withConnection { implicit conn =>
      BankStatementDraftDAO.findByIdForUser(draftId, userId).value.documentId
    }

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
  }

  "POST /bank-statements/drafts" should {
    "create a processing draft and return immediately" in withApiClient { implicit client =>
      createVerifyLoginUser(tokenGenerator).futureValue
      val data = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      data.status must be("processing")
      data.payload must be(None)
    }

    "reject the same file already in the queue" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      client.createBankStatementDraft(sampleBytes, "stmt.pdf").failed.futureValue.getMessage must include("already")
      storedDocuments(userId) must have size 1
    }

    "create an ingested statement document and link the draft to it" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val data = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue

      val documents = storedDocuments(userId)
      documents must have size 1
      documents.head.kind must be(DocumentKind.BankStatementPdf)
      documents.head.status must be(DocumentStatus.Ingested)
      storedDraftDocumentId(data.id, userId) must be(documents.head.id)
    }

    "not enqueue a ProcessDocument job for the statement document" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      val documentId = storedDocuments(userId).head.id

      val count = app.injector.instanceOf[Database].withConnection { implicit conn =>
        SQL"""
          SELECT COUNT(*)
          FROM background_jobs
          WHERE UPPER(type) = ${BackgroundJobType.ProcessDocument.entryName}
            AND payload ->> 'documentId' = ${documentId.toString}
          """.as(SqlParser.scalar[Long].single)
      }
      count must be(0L)
    }

    "reuse the document when the same file is uploaded again after a discard" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val first = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      val documentId = storedDraftDocumentId(first.id, userId)
      client.discardBankStatementDraft(first.id).futureValue.discarded must be(true)

      val second = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      storedDraftDocumentId(second.id, userId) must be(documentId)
      storedDocuments(userId).map(_.id) must be(List(documentId))
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

    "mark the draft's document parsed and link the statement to it" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)
      val documentId = storedDraftDocumentId(id, userId)

      val stmtId = client.confirmBankStatementDraft(id).futureValue.bankStatementId
      val documents = storedDocuments(userId)
      documents.map(_.id) must be(List(documentId))
      documents.head.status must be(DocumentStatus.Parsed)
      val stmtDocumentId = app.injector.instanceOf[Database].withConnection { implicit conn =>
        SQL"SELECT document_id FROM bank_statement WHERE id = ${stmtId.toString}::UUID"
          .as(SqlParser.get[UUID]("document_id").single)
      }
      stmtDocumentId must be(documentId)
    }

    "confirm an uploaded draft into the document created at upload" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val uploaded = client.createBankStatementDraft(sampleBytes, "stmt.pdf").futureValue
      val documentId = storedDraftDocumentId(uploaded.id, userId)
      app.injector.instanceOf[Database].withConnection { implicit conn =>
        BankStatementDraftDAO.markReady(uploaded.id, Json.toJson(reconcilingPayload).toString, Instant.now())
      }

      client.confirmBankStatementDraft(uploaded.id).futureValue
      val documents = storedDocuments(userId)
      documents.map(_.id) must be(List(documentId))
      documents.head.status must be(DocumentStatus.Parsed)
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

    "enqueue a ResolveStatementSuggestions job for the new statement" in withApiClient { implicit client =>
      val userId = createVerifyLoginUser(tokenGenerator).futureValue.id
      val id = seedReadyDraft(userId, reconcilingPayload)

      val stmtId = client.confirmBankStatementDraft(id).futureValue.bankStatementId
      // No API reads background jobs back, so inspect the stored rows directly. The type is matched case-insensitively,
      // like the DAO reads it. Any status: the live executor may already have run the job against MockBedrockClient.
      val count = app.injector.instanceOf[Database].withConnection { implicit conn =>
        SQL"""
          SELECT COUNT(*)
          FROM background_jobs
          WHERE UPPER(type) = ${BackgroundJobType.ResolveStatementSuggestions.entryName}
            AND payload ->> 'bankStatementId' = ${stmtId.toString}
          """.as(SqlParser.scalar[Long].single)
      }
      count must be(1L)
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
