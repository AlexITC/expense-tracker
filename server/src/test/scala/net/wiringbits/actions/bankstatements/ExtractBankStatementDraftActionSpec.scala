package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.apis.{JevChoice, JevClient, JevRateLimitedException}
import net.wiringbits.config.JevConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.JevApiKey
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification}
import net.wiringbits.repositories.models.BankStatementDraft
import net.wiringbits.services.AiStatementExtractor.{ExtractionResult, Meta}
import net.wiringbits.services.{BlobStorage, DocumentClassifier, StatementExtractionService}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import utils.RepositoryUtils

import java.io.ByteArrayOutputStream
import java.time.Clock
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Using

class ExtractBankStatementDraftActionSpec extends RepositorySpec with RepositoryUtils with Matchers with MockitoSugar {

  private def result(source: Option[ExtractionSource]): ExtractionResult =
    ExtractionResult(
      reconciled = true,
      discrepancy = None,
      model = "test",
      transactions = Nil,
      metadata = Meta(None, None, None, None, None, None, None, None),
      source = source
    )

  "toPayload" should {
    "carry the stage the extraction ladder tagged on its result" in {
      ExtractBankStatementDraftAction.toPayload(result(Some(ExtractionSource.PdfText))).stage must be(
        Some(ExtractionSource.PdfText)
      )
    }

    "leave the stage empty when the result is untagged" in {
      ExtractBankStatementDraftAction.toPayload(result(None)).stage must be(None)
    }
  }

  private class InMemoryBlobStorage(initial: Map[String, Array[Byte]]) extends BlobStorage {
    private val blobs = TrieMap.from(initial)
    override def put(storageKey: String, bytes: Array[Byte]): Future[Unit] =
      Future.successful(blobs.update(storageKey, bytes))
    override def exists(storageKey: String): Future[Boolean] = Future.successful(blobs.contains(storageKey))
    override def get(storageKey: String): Future[Option[Array[Byte]]] = Future.successful(blobs.get(storageKey))
    override def delete(storageKey: String): Future[Unit] = Future.successful(blobs.remove(storageKey)).map(_ => ())
  }

  // Answers each JEV question with `respond(questionName)`.
  private class StubJevClient(respond: String => Future[JevChoice]) extends JevClient {
    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = respond(questionName)
  }

  private def stubJev(typeChoice: String, typeConfidence: BigDecimal) =
    new StubJevClient({
      case DocumentClassifier.TypeQuestion => Future.successful(JevChoice(typeChoice, typeConfidence, Map.empty))
      case _ => Future.successful(JevChoice("clean", BigDecimal("0.99"), Map.empty))
    })

  private val failingJev = new StubJevClient(_ => Future.failed(new JevRateLimitedException("rate limited")))

  private val jevConfig =
    JevConfig(
      JevApiKey("test"),
      "https://example.invalid",
      "jev-test",
      JevConfig.Real,
      minConfidence = BigDecimal("0.85")
    )

  private val storageKey = "statements/test.pdf"

  // A one-page PDF with a text layer long enough for PdfTextExtractor's text branch; synthetic content only.
  private lazy val statementPdf: Array[Byte] = Using.resource(new PDDocument()) { doc =>
    val page = new PDPage()
    doc.addPage(page)
    Using.resource(new PDPageContentStream(doc, page)) { content =>
      content.beginText()
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10)
      content.newLineAtOffset(50, 700)
      List(
        "EXAMPLE BANK - ACCOUNT STATEMENT",
        "Period 01/01/2026 - 31/01/2026",
        "02/01/2026 SUPERMARKET EXAMPLE -25.40 974.60",
        "05/01/2026 SALARY TRANSFER 1500.00 2474.60",
        "10/01/2026 ELECTRICITY COMPANY -60.00 2414.60"
      ).foreach { line =>
        content.showText(line)
        content.newLineAtOffset(0, -14)
      }
      content.endText()
    }
    val out = new ByteArrayOutputStream()
    doc.save(out)
    out.toByteArray
  }

  private def extractionService(): StatementExtractionService = {
    val service = mock[StatementExtractionService]
    when(service.extract(anyString(), anyString(), anyString(), anyString()))
      .thenReturn(Future.successful(result(Some(ExtractionSource.PdfText))))
    service
  }

  private def action(jevClient: JevClient, service: StatementExtractionService)(using
      repositories: RepositoryComponents
  ) =
    new ExtractBankStatementDraftAction(
      repositories.bankStatementDrafts,
      repositories.documents,
      new InMemoryBlobStorage(Map(storageKey -> statementPdf)),
      service,
      new DocumentClassifier(jevClient, jevConfig)
    )(using executionContext, Clock.systemUTC())

  "apply" should {
    "fail the draft without extracting when it is confidently an invoice" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
        val service = extractionService()

        action(stubJev("invoice", BigDecimal("1.0")), service).apply(draft.id).futureValue

        val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
        found.status must be(BankStatementDraft.Status.Failed)
        found.error must be(Some("This looks like an invoice, not a bank statement."))
        val doc = repositories.documents.findById(draft.documentId).futureValue.value
        doc.aiClassification must be(Some(DocumentClass.Invoice))
        doc.aiClassificationConfidence must be(Some(BigDecimal("1.0")))
        verify(service, never()).extract(any(), any(), any(), any())
    }

    "fail the draft without extracting when it is confidently a purchase ticket" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
        val service = extractionService()

        action(stubJev("purchase_ticket", BigDecimal("1.0")), service).apply(draft.id).futureValue

        val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
        found.status must be(BankStatementDraft.Status.Failed)
        found.error must be(Some("This looks like a purchase ticket, not a bank statement."))
        repositories.documents
          .findById(draft.documentId)
          .futureValue
          .value
          .aiClassification must be(Some(DocumentClass.PurchaseTicket))
        verify(service, never()).extract(any(), any(), any(), any())
    }

    "fail the draft without extracting when it is confidently some other document" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
        val service = extractionService()

        action(stubJev("other", BigDecimal("1.0")), service).apply(draft.id).futureValue

        val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
        found.status must be(BankStatementDraft.Status.Failed)
        found.error must be(Some("This doesn't look like a bank statement."))
        repositories.documents
          .findById(draft.documentId)
          .futureValue
          .value
          .aiClassification must be(Some(DocumentClass.Other))
        verify(service, never()).extract(any(), any(), any(), any())
    }

    "extract and store the classification when it is a bank statement" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
      val service = extractionService()

      action(stubJev("bank_statement", BigDecimal("0.97")), service).apply(draft.id).futureValue

      val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
      found.status must be(BankStatementDraft.Status.Ready)
      found.payload must not be empty
      repositories.documents.findById(draft.documentId).futureValue.value.classification must be(
        Some(DocumentClassification(DocumentClass.BankStatement, BigDecimal("0.97"), false, BigDecimal("0.99")))
      )
      verify(service, times(1)).extract(any(), any(), any(), any())
    }

    "extract and store the observed classification when it is below the threshold" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
        val service = extractionService()

        action(stubJev("invoice", BigDecimal("0.5")), service).apply(draft.id).futureValue

        val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
        found.status must be(BankStatementDraft.Status.Ready)
        val doc = repositories.documents.findById(draft.documentId).futureValue.value
        doc.aiClassification must be(Some(DocumentClass.Invoice))
        doc.aiClassificationConfidence must be(Some(BigDecimal("0.5")))
        verify(service, times(1)).extract(any(), any(), any(), any())
    }

    "extract without storing a classification when JEV fails" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = createBankStatementDraft(user.id, storageKey = storageKey).futureValue
      val service = extractionService()

      action(failingJev, service).apply(draft.id).futureValue

      val found = repositories.bankStatementDrafts.findById(draft.id).futureValue.value
      found.status must be(BankStatementDraft.Status.Ready)
      repositories.documents.findById(draft.documentId).futureValue.value.classification must be(None)
      verify(service, times(1)).extract(any(), any(), any(), any())
    }
  }
}
