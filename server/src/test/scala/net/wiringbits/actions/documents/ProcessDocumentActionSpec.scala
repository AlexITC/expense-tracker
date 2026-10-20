package net.wiringbits.actions.documents

import net.wiringbits.apis.{JevChoice, JevClient, JevRateLimitedException, MockTextractClient, TelegramApi}
import net.wiringbits.config.JevConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.JevApiKey
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind, DocumentStatus}
import net.wiringbits.services.{BlobStorage, DocumentClassifier, DocumentTelegramNotifier}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.mockito.ArgumentMatchers.{anyLong, anyString}
import org.mockito.Mockito.*
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import utils.RepositoryUtils

import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Using

class ProcessDocumentActionSpec extends RepositorySpec with RepositoryUtils with Matchers with MockitoSugar {

  private class InMemoryBlobStorage(initial: Map[String, Array[Byte]]) extends BlobStorage {
    private val blobs = TrieMap.from(initial)
    override def put(storageKey: String, bytes: Array[Byte]): Future[Unit] =
      Future.successful(blobs.update(storageKey, bytes))
    override def exists(storageKey: String): Future[Boolean] = Future.successful(blobs.contains(storageKey))
    override def get(storageKey: String): Future[Option[Array[Byte]]] = Future.successful(blobs.get(storageKey))
    override def delete(storageKey: String): Future[Unit] = Future.successful(blobs.remove(storageKey)).map(_ => ())
  }

  // Answers each JEV question with `respond(questionName)` and counts the calls.
  private class StubJevClient(respond: String => Future[JevChoice]) extends JevClient {
    val calls = new AtomicInteger(0)
    override def choose(
        state: String,
        questionName: String,
        instructions: String,
        criteria: Map[String, String]
    ): Future[JevChoice] = {
      calls.incrementAndGet()
      respond(questionName)
    }
  }

  private class RecordingTextractClient extends MockTextractClient {
    val expenseAnalyses = new AtomicInteger(0)
    override def startExpenseAnalysis(sha256: String, storageKey: String): Future[String] = {
      expenseAnalyses.incrementAndGet()
      super.startExpenseAnalysis(sha256, storageKey)
    }
  }

  private def stubJev(typeChoice: String, typeConfidence: BigDecimal = BigDecimal("1.0")) =
    new StubJevClient({
      case DocumentClassifier.TypeQuestion => Future.successful(JevChoice(typeChoice, typeConfidence, Map.empty))
      case _ => Future.successful(JevChoice("clean", BigDecimal("0.99"), Map.empty))
    })

  private def failingJev = new StubJevClient(_ => Future.failed(new JevRateLimitedException("rate limited")))

  private val jevConfig =
    JevConfig(
      JevApiKey("test"),
      "https://example.invalid",
      "jev-test",
      JevConfig.Real,
      minConfidence = BigDecimal("0.85")
    )

  // A one-page PDF with a text layer long enough for PdfTextExtractor's text branch; synthetic content only.
  private lazy val textPdf: Array[Byte] = Using.resource(new PDDocument()) { doc =>
    val page = new PDPage()
    doc.addPage(page)
    Using.resource(new PDPageContentStream(doc, page)) { content =>
      content.beginText()
      content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10)
      content.newLineAtOffset(50, 700)
      List(
        "EXAMPLE BANK - CARD PAYMENT",
        "Merchant: EXAMPLE STORE",
        "Card: **** **** **** 0000",
        "Authorization: 000000 TPV: 00000000",
        "Amount: 12.34 EUR - APPROVED"
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

  // Not a PDF, like a JPEG photo sent over Telegram: PdfTextExtractor gives Left.
  private val photoBytes: Array[Byte] = Array[Byte](0xff.toByte, 0xd8.toByte, 0xff.toByte, 0xe0.toByte, 1, 2, 3, 4)

  private val chatId = 4242L

  private case class Fixture(
      action: ProcessDocumentAction,
      jev: StubJevClient,
      textract: RecordingTextractClient,
      telegramApi: TelegramApi
  )

  // Fails every read, like an S3 outage.
  private object FailingBlobStorage extends InMemoryBlobStorage(Map.empty) {
    override def get(storageKey: String): Future[Option[Array[Byte]]] =
      Future.failed(new RuntimeException("storage unavailable"))
  }

  private def fixture(
      jev: StubJevClient,
      bytes: Array[Byte],
      storageKey: String = "",
      blobStorage: Option[BlobStorage] = None,
      sendResult: Future[Unit] = Future.unit
  )(using
      repositories: RepositoryComponents
  ): Fixture = {
    val textract = new RecordingTextractClient
    val telegramApi = mock[TelegramApi]
    when(telegramApi.sendMessage(anyLong(), anyString())).thenReturn(sendResult)
    val action = new ProcessDocumentAction(
      repositories.documents,
      repositories.textractCache,
      repositories.textractJobs,
      repositories.backgroundJobs,
      textract,
      blobStorage.getOrElse(new InMemoryBlobStorage(Map(storageKey -> bytes))),
      new DocumentClassifier(jev, jevConfig),
      new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)
    )(using executionContext, Clock.systemUTC())
    Fixture(action, jev, textract, telegramApi)
  }

  "apply" should {
    "reject a bank voucher receipt and tell the linked Telegram chat, without calling Textract" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val receipt = createReceipt(user.id).futureValue
        repositories.telegramReceipt.create(UUID.randomUUID(), receipt.id, chatId).futureValue
        val f = fixture(stubJev("bank_voucher"), textPdf)

        f.action(receipt.documentId).futureValue

        val doc = repositories.documents.findById(receipt.documentId).futureValue.value
        doc.status must be(DocumentStatus.Unsupported)
        doc.aiClassification must be(Some(DocumentClass.BankVoucher))
        doc.aiClassificationConfidence must be(Some(BigDecimal("1.0")))
        verify(f.telegramApi).sendMessage(
          chatId,
          "This looks like a bank voucher, not a purchase ticket — please send the itemized ticket."
        )
        f.textract.expenseAnalyses.get() must be(0)
        repositories.textractJobs.findByDocumentId(receipt.documentId).futureValue must be(None)
    }

    "reject a bank voucher receipt without a Telegram link, sending nothing" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val receipt = createReceipt(user.id).futureValue
        val f = fixture(stubJev("bank_voucher"), textPdf)

        f.action(receipt.documentId).futureValue

        val doc = repositories.documents.findById(receipt.documentId).futureValue.value
        doc.status must be(DocumentStatus.Unsupported)
        verify(f.telegramApi, never()).sendMessage(anyLong(), anyString())
        f.textract.expenseAnalyses.get() must be(0)
    }

    "analyze a purchase ticket with Textract" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val f = fixture(stubJev("purchase_ticket"), textPdf)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      doc.aiClassification must be(Some(DocumentClass.PurchaseTicket))
      f.textract.expenseAnalyses.get() must be(1)
      repositories.textractJobs.findByDocumentId(receipt.documentId).futureValue must not be empty
    }

    "analyze with Textract when the class is below the confidence threshold" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val receipt = createReceipt(user.id).futureValue
        val f = fixture(stubJev("bank_voucher", BigDecimal("0.5")), textPdf)

        f.action(receipt.documentId).futureValue

        val doc = repositories.documents.findById(receipt.documentId).futureValue.value
        doc.status must be(DocumentStatus.Processing)
        doc.aiClassification must be(Some(DocumentClass.BankVoucher))
        doc.aiClassificationConfidence must be(Some(BigDecimal("0.5")))
        f.textract.expenseAnalyses.get() must be(1)
    }

    "analyze with Textract when JEV fails" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val f = fixture(failingJev, textPdf)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      doc.aiClassification must be(None)
      f.textract.expenseAnalyses.get() must be(1)
    }

    "analyze an image with Textract without classifying it" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val f = fixture(stubJev("bank_voucher"), photoBytes)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      doc.aiClassification must be(None)
      f.jev.calls.get() must be(0)
      f.textract.expenseAnalyses.get() must be(1)
    }

    "analyze an invoice upload classified as an invoice with Textract" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val document =
        createDocument(user.id, storageKey = "invoices/test.pdf", kind = DocumentKind.Invoice).futureValue
      val f = fixture(stubJev("invoice"), textPdf, storageKey = "invoices/test.pdf")

      f.action(document.id).futureValue

      val doc = repositories.documents.findById(document.id).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      doc.aiClassification must be(Some(DocumentClass.Invoice))
      f.textract.expenseAnalyses.get() must be(1)
    }

    "reuse a stored classification without asking JEV again" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.documents
        .updateClassification(
          receipt.documentId,
          user.id,
          DocumentClassification(DocumentClass.BankVoucher, BigDecimal("0.97"), false, BigDecimal("0.99"))
        )
        .futureValue
      val f = fixture(stubJev("purchase_ticket"), textPdf)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Unsupported)
      doc.aiClassification must be(Some(DocumentClass.BankVoucher))
      f.jev.calls.get() must be(0)
      f.textract.expenseAnalyses.get() must be(0)
      verify(f.telegramApi, never()).sendMessage(anyLong(), anyString())
    }

    "reject a receipt classified as an invoice" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val f = fixture(stubJev("invoice"), textPdf)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Unsupported)
      doc.aiClassification must be(Some(DocumentClass.Invoice))
      f.textract.expenseAnalyses.get() must be(0)
    }

    "reject an invoice upload classified as a bank voucher" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val document =
        createDocument(user.id, storageKey = "invoices/test.pdf", kind = DocumentKind.Invoice).futureValue
      val f = fixture(stubJev("bank_voucher"), textPdf, storageKey = "invoices/test.pdf")

      f.action(document.id).futureValue

      val doc = repositories.documents.findById(document.id).futureValue.value
      doc.status must be(DocumentStatus.Unsupported)
      f.textract.expenseAnalyses.get() must be(0)
    }

    "analyze with Textract without classifying when the bytes are missing" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val receipt = createReceipt(user.id).futureValue
        val f = fixture(stubJev("bank_voucher"), textPdf, storageKey = "elsewhere.pdf")

        f.action(receipt.documentId).futureValue

        val doc = repositories.documents.findById(receipt.documentId).futureValue.value
        doc.status must be(DocumentStatus.Processing)
        doc.aiClassification must be(None)
        f.jev.calls.get() must be(0)
        f.textract.expenseAnalyses.get() must be(1)
    }

    "analyze with Textract when reading the bytes fails" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val f = fixture(stubJev("bank_voucher"), textPdf, blobStorage = Some(FailingBlobStorage))

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      doc.aiClassification must be(None)
      f.jev.calls.get() must be(0)
      f.textract.expenseAnalyses.get() must be(1)
    }

    "still reject the document when the Telegram message fails to send" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.telegramReceipt.create(UUID.randomUUID(), receipt.id, chatId).futureValue
      val f = fixture(
        stubJev("bank_voucher"),
        textPdf,
        sendResult = Future.failed(new RuntimeException("telegram unavailable"))
      )

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Unsupported)
      verify(f.telegramApi).sendMessage(anyLong(), anyString())
      f.textract.expenseAnalyses.get() must be(0)
    }

    "not reject on a stored classification below the threshold" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.documents
        .updateClassification(
          receipt.documentId,
          user.id,
          DocumentClassification(DocumentClass.BankVoucher, BigDecimal("0.5"), false, BigDecimal("0.99"))
        )
        .futureValue
      val f = fixture(stubJev("bank_voucher"), textPdf)

      f.action(receipt.documentId).futureValue

      val doc = repositories.documents.findById(receipt.documentId).futureValue.value
      doc.status must be(DocumentStatus.Processing)
      f.jev.calls.get() must be(0)
      f.textract.expenseAnalyses.get() must be(1)
    }
  }

  "notATicketMessage" should {
    "word each rejected class for the user" in {
      DocumentTelegramNotifier.notATicketMessage(DocumentClass.Invoice) must be(
        "This looks like an invoice, not a purchase ticket — please send the itemized ticket."
      )
      DocumentTelegramNotifier.notATicketMessage(DocumentClass.Other) must be(
        "This doesn't look like a purchase ticket — please send the itemized ticket."
      )
    }
  }
}
