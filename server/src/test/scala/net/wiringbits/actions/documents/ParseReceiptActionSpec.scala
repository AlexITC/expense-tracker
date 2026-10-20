package net.wiringbits.actions.documents

import net.wiringbits.apis.{TelegramApi, TextractApiType}
import net.wiringbits.config.WebAppConfig
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.{
  DocumentsRepository,
  ImportRuleRepository,
  MerchantsRepository,
  ReceiptRepository,
  TelegramReceiptRepository,
  TextractCacheRepository
}
import net.wiringbits.repositories.models.{Document, Receipt, TelegramReceipt, TextractCache}
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json

import java.time.{Clock, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ParseReceiptActionSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  private val minimalTextractJson =
    Json.parse("""{"ExpenseDocuments":[{"SummaryFields":[],"LineItemGroups":[]}]}""")

  private val webAppConfig = WebAppConfig("https://app.example.com")

  private def stubDocument(
      docId: UUID,
      userId: UUID,
      sha256: String = "deadbeef",
      kind: DocumentKind = DocumentKind.Receipt,
      status: DocumentStatus = DocumentStatus.Ingested
  ): Document =
    Document(
      docId,
      userId,
      sha256,
      "key",
      kind,
      "receipt.jpg",
      status,
      None,
      Instant.now(),
      Instant.now(),
      None,
      None,
      None,
      None
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
      Instant.now(),
      Instant.now(),
      "receipt.jpg",
      DocumentStatus.Ingested
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
      telegramApi: TelegramApi
  ): ParseReceiptAction =
    new ParseReceiptAction(
      docRepo,
      receiptRepo,
      merchantRepo,
      ruleRepo,
      cacheRepo,
      telegramReceiptRepo,
      telegramApi,
      webAppConfig
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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(123L))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(None))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessage(anyLong(), anyString())).thenReturn(Future.unit)

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(receiptRepo.findByDocumentId(docId)).thenReturn(Future.successful(Some(receipt)))
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(None))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

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
      when(docRepo.updateStatus(any(), any(), any())).thenReturn(Future.unit)
      when(telegramReceiptRepo.findByReceiptId(receiptId)).thenReturn(Future.successful(Some(tr)))
      when(telegramApi.sendMessageWithWebAppButton(anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(Future.successful(123L))

      newAction(docRepo, receiptRepo, merchantRepo, ruleRepo, cacheRepo, telegramReceiptRepo, telegramApi)(
        docId
      ).futureValue

      verify(telegramApi).sendMessageWithWebAppButton(
        chatId,
        "Receipt from Starbucks parsed.",
        s"${webAppConfig.host}/api/telegram/webapp?receipt=$receiptId",
        "Create Expense"
      )
    }
  }
}
