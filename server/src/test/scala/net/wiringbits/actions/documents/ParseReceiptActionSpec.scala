package net.wiringbits.actions.documents

import net.wiringbits.apis.{JevChoice, JevClient, JevRateLimitedException, TelegramApi, TextractApiType}
import net.wiringbits.config.{JevConfig, WebAppConfig}
import net.wiringbits.models.JevApiKey
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind, DocumentStatus}
import net.wiringbits.repositories.{
  DocumentsRepository,
  ImportRuleRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository,
  TextractCacheRepository
}
import net.wiringbits.repositories.models.{Document, Receipt, TelegramReceipt, TextractCache}
import net.wiringbits.services.{DocumentClassifier, DocumentParser, DocumentTelegramNotifier}
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ParseReceiptActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val minimalTextractJson =
    Json.parse("""{"ExpenseDocuments":[{"SummaryFields":[],"LineItemGroups":[]}]}""")

  private val webAppConfig = WebAppConfig("https://app.example.com")

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

  private def stubJev(typeChoice: String) =
    new StubJevClient({
      case DocumentClassifier.TypeQuestion => Future.successful(JevChoice(typeChoice, BigDecimal("1.0"), Map.empty))
      case _ => Future.successful(JevChoice("clean", BigDecimal("0.99"), Map.empty))
    })

  private def failingJev = new StubJevClient(_ => Future.failed(new JevRateLimitedException("rate limited")))

  private def jevConfig(mode: JevConfig.Mode) =
    JevConfig(JevApiKey("test"), "https://example.invalid", "jev-test", mode, minConfidence = BigDecimal("0.85"))

  // jev.mode=mock never calls JEV, so the cases that don't exercise the classification ignore it.
  private val mockModeClassifier = new DocumentClassifier(failingJev, jevConfig(JevConfig.Mock))

  private def realClassifier(jev: JevClient) = new DocumentClassifier(jev, jevConfig(JevConfig.Real))

  // A card-payment voucher as Textract reads it; synthetic data only.
  private val voucherTextractJson = Json.parse(
    """{
      |  "ExpenseDocuments": [
      |    {
      |      "SummaryFields": [
      |        {
      |          "Type": { "Text": "VENDOR_NAME", "Confidence": 99.0 },
      |          "ValueDetection": { "Text": "EXAMPLE BANK", "Confidence": 98.0 }
      |        },
      |        {
      |          "Type": { "Text": "TOTAL", "Confidence": 99.0 },
      |          "LabelDetection": { "Text": "IMPORTE" },
      |          "ValueDetection": { "Text": "12.34", "Confidence": 97.0 }
      |        }
      |      ],
      |      "LineItemGroups": []
      |    }
      |  ]
      |}""".stripMargin
  )

  private def stubDocument(
      docId: UUID,
      userId: UUID,
      sha256: String = "deadbeef",
      kind: DocumentKind = DocumentKind.Receipt,
      classification: Option[DocumentClassification] = None
  ): Document =
    Document(
      docId,
      userId,
      sha256,
      "key",
      kind,
      "receipt.jpg",
      Instant.now(),
      Instant.now(),
      classification.map(_.documentClass),
      classification.map(_.confidence),
      classification.map(_.injectionSuspected),
      classification.map(_.injectionConfidence)
    )

  private def stubReceipt(receiptId: UUID, userId: UUID, docId: UUID): Receipt =
    Receipt(
      receiptId,
      userId,
      docId,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      manuallyEdited = false,
      None,
      DocumentStatus.Ingested,
      Instant.now(),
      Instant.now(),
      "receipt.jpg"
    )

  private def stubTextractCache(sha256: String): TextractCache =
    TextractCache(sha256, TextractApiType.AnalyzeExpense, minimalTextractJson, Instant.now())

  private def stubTelegramReceipt(receiptId: UUID, chatId: Long): TelegramReceipt =
    TelegramReceipt(UUID.randomUUID(), receiptId, chatId, None, Instant.now(), Instant.now())

  private def newAction(
      docRepo: DocumentsRepository,
      receiptRepo: ReceiptRepository,
      merchantRepo: MerchantsRepository,
      ruleRepo: ImportRuleRepository,
      cacheRepo: TextractCacheRepository,
      telegramReceiptRepo: TelegramReceiptRepository,
      telegramApi: TelegramApi,
      classifier: DocumentClassifier = mockModeClassifier
  ): ParseReceiptAction =
    new ParseReceiptAction(
      docRepo,
      receiptRepo,
      merchantRepo,
      ruleRepo,
      cacheRepo,
      telegramReceiptRepo,
      telegramApi,
      webAppConfig,
      classifier,
      new DocumentTelegramNotifier(receiptRepo, telegramReceiptRepo, telegramApi)
    )(using global, Clock.systemUTC())

  "apply" should {
    "send Create Expense button when receipt is parsed and telegram_receipt exists" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val chatId = 42L
      val userId = UUID.randomUUID()

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId)
      val receipt = stubReceipt(receiptId, userId, docId)
      val tr = stubTelegramReceipt(receiptId, chatId)

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(Some(stubTextractCache(doc.sha256))))
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(receiptRepo.updateParsed(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Future.unit)
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(123L))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Parsed)
      verify(telegramApi).sendMessageWithWebAppButton(
        chatId,
        "Receipt parsed.",
        s"${webAppConfig.host}/api/telegram/webapp?receipt=$receiptId",
        "Create Expense"
      )
    }

    "does not send notification when telegram_receipt is absent on success" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val userId = UUID.randomUUID()

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId)
      val receipt = stubReceipt(receiptId, userId, docId)

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(Some(stubTextractCache(doc.sha256))))
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(receiptRepo.updateParsed(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Future.unit)
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(None))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Parsed)
      verify(telegramApi, never()).sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString())
    }

    "sends error message when document kind is unsupported and telegram_receipt exists" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val chatId = 99L
      val userId = UUID.randomUUID()

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId, kind = DocumentKind.BankStatementPdf)
      val receipt = stubReceipt(receiptId, userId, docId)
      val tr = stubTelegramReceipt(receiptId, chatId)

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(Some(stubTextractCache(doc.sha256))))
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Unsupported)
      verify(telegramApi).sendMessage(chatId, "Could not parse the receipt: unsupported document format.")
    }

    "sends error message when no textract cache and telegram_receipt exists" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val chatId = 55L
      val userId = UUID.randomUUID()

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId)
      val receipt = stubReceipt(receiptId, userId, docId)
      val tr = stubTelegramReceipt(receiptId, chatId)

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(None))
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Unsupported)
      verify(telegramApi).sendMessage(chatId, "Could not parse the receipt: analysis data not found.")
    }

    "does not send notification when telegram_receipt is absent on error" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val userId = UUID.randomUUID()

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId, kind = DocumentKind.BankStatementPdf)
      val receipt = stubReceipt(receiptId, userId, docId)

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(Some(stubTextractCache(doc.sha256))))
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(None))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Unsupported)
      verify(telegramApi, never()).sendMessage(anyLong(), anyString())
    }

    "send Create Expense button with vendor name in summary when receipt has a vendor name" in {
      val docId = UUID.randomUUID()
      val receiptId = UUID.randomUUID()
      val chatId = 42L
      val userId = UUID.randomUUID()

      val vendorTextractJson = Json.parse(
        """{"ExpenseDocuments":[{"SummaryFields":[{"Type":{"Text":"VENDOR_NAME","Confidence":99.9},"ValueDetection":{"Text":"Starbucks"}}],"LineItemGroups":[]}]}"""
      )

      val docRepo = mock[DocumentsRepository]
      val receiptRepo = mock[ReceiptRepository]
      val merchantRepo = mock[MerchantsRepository]
      val ruleRepo = mock[ImportRuleRepository]
      val cacheRepo = mock[TextractCacheRepository]
      val telegramReceiptRepo = mock[TelegramReceiptRepository]
      val telegramApi = mock[TelegramApi]

      val doc = stubDocument(docId, userId)
      val receipt = stubReceipt(receiptId, userId, docId)
      val tr = stubTelegramReceipt(receiptId, chatId)
      val merchantId = UUID.randomUUID()

      when(docRepo.findById(docId)).thenReturn(Future.successful(Some(doc)))
      when(cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(
          Future.successful(
            Some(TextractCache(doc.sha256, TextractApiType.AnalyzeExpense, vendorTextractJson, Instant.now()))
          )
        )
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(receiptRepo.updateParsed(any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
      when(ruleRepo.all(any())).thenReturn(Future.successful(Nil))
      when(merchantRepo.createPendingIfAbsent(any(), anyString())).thenReturn(Future.successful(merchantId))
      when(receiptRepo.setMerchantId(any(), any())).thenReturn(Future.unit)
      when(ruleRepo.existsByUserPatternMerchant(any(), anyString(), any(), any())).thenReturn(Future.successful(false))
      when(ruleRepo.create(any(), any(), anyString(), any(), any())).thenReturn(Future.successful(UUID.randomUUID()))
      when(receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(123L))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(receiptRepo).updateStatus(receiptId, userId, DocumentStatus.Parsed)
      verify(telegramApi).sendMessageWithWebAppButton(
        chatId,
        "Receipt from Starbucks parsed.",
        s"${webAppConfig.host}/api/telegram/webapp?receipt=$receiptId",
        "Create Expense"
      )
    }
  }

  "apply (advisory classification)" should {
    // Mocks for a receipt whose parse succeeds and that came from a Telegram chat.
    case class Mocks(
        docRepo: DocumentsRepository,
        receiptRepo: ReceiptRepository,
        merchantRepo: MerchantsRepository,
        ruleRepo: ImportRuleRepository,
        cacheRepo: TextractCacheRepository,
        telegramReceiptRepo: TelegramReceiptRepository,
        telegramApi: TelegramApi
    ) {
      def action(classifier: DocumentClassifier): ParseReceiptAction =
        newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi, classifier)
    }

    val chatId = 77L

    def mocksFor(doc: Document): Mocks = {
      val receipt = stubReceipt(UUID.randomUUID(), doc.userId, doc.id)
      val mocks = Mocks(
        mock[DocumentsRepository],
        mock[ReceiptRepository],
        mock[MerchantsRepository],
        mock[ImportRuleRepository],
        mock[TextractCacheRepository],
        mock[TelegramReceiptRepository],
        mock[TelegramApi]
      )
      when(mocks.docRepo.findById(doc.id)).thenReturn(Future.successful(Some(doc)))
      when(mocks.cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(
          Future.successful(
            Some(TextractCache(doc.sha256, TextractApiType.AnalyzeExpense, voucherTextractJson, Instant.now()))
          )
        )
      when(mocks.docRepo.updateClassification(any(), any(), any())).thenReturn(Future.unit)
      when(mocks.receiptRepo.findByDocumentId(doc.id)).thenReturn(Future.successful(Some(receipt)))
      when(mocks.receiptRepo.updateParsed(any(), any(), any(), any(), any(), any(), any())).thenReturn(Future.unit)
      when(mocks.ruleRepo.all(any())).thenReturn(Future.successful(Nil))
      when(mocks.merchantRepo.createPendingIfAbsent(any(), anyString()))
        .thenReturn(Future.successful(UUID.randomUUID()))
      when(mocks.receiptRepo.setMerchantId(any(), any())).thenReturn(Future.unit)
      when(mocks.ruleRepo.existsByUserPatternMerchant(any(), anyString(), any(), any()))
        .thenReturn(Future.successful(true))
      when(mocks.receiptRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(mocks.telegramReceiptRepo.findByReceiptId(receipt.id))
        .thenReturn(Future.successful(Some(stubTelegramReceipt(receipt.id, chatId))))
      when(mocks.telegramApi.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)
      when(mocks.telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(123L))
      mocks
    }

    def verifyParsed(mocks: Mocks, doc: Document): Unit = {
      verify(mocks.receiptRepo).updateParsed(any(), any(), any(), any(), any(), any(), any())
      verify(mocks.receiptRepo).updateStatus(any(), any(), org.mockito.ArgumentMatchers.eq(DocumentStatus.Parsed))
      verify(mocks.telegramApi).sendMessageWithWebAppButton(
        org.mockito.ArgumentMatchers.eq(chatId),
        anyString(),
        anyString(),
        anyString()
      )
    }

    "persist the classification, advise the user and still parse a document that is not a ticket" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)

      mocks.action(realClassifier(stubJev("bank_voucher")))(doc.id).futureValue

      verify(mocks.docRepo).updateClassification(
        doc.id,
        doc.userId,
        DocumentClassification(DocumentClass.BankVoucher, BigDecimal("1.0"), false, BigDecimal("0.99"))
      )
      verify(mocks.telegramApi)
        .sendMessage(chatId, DocumentTelegramNotifier.notATicketMessage(DocumentClass.BankVoucher))
      verifyParsed(mocks, doc)
    }

    "persist a purchase ticket classification without advising the user" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)

      mocks.action(realClassifier(stubJev("purchase_ticket")))(doc.id).futureValue

      verify(mocks.docRepo).updateClassification(
        doc.id,
        doc.userId,
        DocumentClassification(DocumentClass.PurchaseTicket, BigDecimal("1.0"), false, BigDecimal("0.99"))
      )
      verify(mocks.telegramApi, never()).sendMessage(anyLong(), anyString())
      verifyParsed(mocks, doc)
    }

    "not classify a document that was already classified" in {
      val stored = DocumentClassification(DocumentClass.PurchaseTicket, BigDecimal("0.95"), false, BigDecimal("0.99"))
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID(), classification = Some(stored))
      val mocks = mocksFor(doc)
      val jev = stubJev("bank_voucher")

      mocks.action(realClassifier(jev))(doc.id).futureValue

      jev.calls.get() mustBe 0
      verify(mocks.docRepo, never()).updateClassification(any(), any(), any())
      verify(mocks.telegramApi, never()).sendMessage(anyLong(), anyString())
      verifyParsed(mocks, doc)
    }

    "parse normally without persisting anything when JEV fails (the classifier answers inconclusive)" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)
      val jev = failingJev

      mocks.action(realClassifier(jev))(doc.id).futureValue

      jev.calls.get() mustBe 1
      verify(mocks.docRepo, never()).updateClassification(any(), any(), any())
      verify(mocks.telegramApi, never()).sendMessage(anyLong(), anyString())
      verifyParsed(mocks, doc)
    }

    "still parse when storing the classification fails" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)
      when(mocks.docRepo.updateClassification(any(), any(), any()))
        .thenReturn(Future.failed(new RuntimeException("db down")))

      mocks.action(realClassifier(stubJev("bank_voucher")))(doc.id).futureValue

      verify(mocks.telegramApi, never()).sendMessage(anyLong(), anyString())
      verifyParsed(mocks, doc)
    }

    "still parse when sending the advisory message fails" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)
      when(mocks.telegramApi.sendMessage(anyLong(), anyString()))
        .thenReturn(Future.failed(new RuntimeException("telegram unavailable")))

      mocks.action(realClassifier(stubJev("bank_voucher")))(doc.id).futureValue

      verify(mocks.docRepo).updateClassification(any(), any(), any())
      verifyParsed(mocks, doc)
    }

    "not classify a document whose Textract response has no text" in {
      val doc = stubDocument(UUID.randomUUID(), UUID.randomUUID())
      val mocks = mocksFor(doc)
      when(mocks.cacheRepo.find(doc.sha256, TextractApiType.AnalyzeExpense))
        .thenReturn(Future.successful(Some(stubTextractCache(doc.sha256))))
      val jev = stubJev("other")

      mocks.action(realClassifier(jev))(doc.id).futureValue

      jev.calls.get() mustBe 0
      verify(mocks.docRepo, never()).updateClassification(any(), any(), any())
      verify(mocks.telegramApi, never()).sendMessage(anyLong(), anyString())
      verifyParsed(mocks, doc)
    }
  }
}
