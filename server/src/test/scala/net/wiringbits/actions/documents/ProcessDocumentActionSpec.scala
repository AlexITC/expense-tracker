package net.wiringbits.actions.documents

import net.wiringbits.apis.TextractApiType
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.TextractCache
import net.wiringbits.services.DocumentTelegramNotifier
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.Json
import utils.{FailingTextractClient, FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.{Clock, Instant}
import java.util.UUID

class ProcessDocumentActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction(
      notifier: Option[DocumentTelegramNotifier] = None
  )(using repositories: RepositoryComponents): ProcessDocumentAction =
    new ProcessDocumentAction(
      repositories.documents,
      repositories.receipts,
      repositories.textractCache,
      repositories.textractJobs,
      repositories.backgroundJobs,
      new FailingTextractClient,
      noOpBlobStorage,
      mockDocumentClassifier,
      notifier.getOrElse(documentTelegramNotifier)
    )(using executionContext, Clock.systemUTC())

  private def fakeNotifier(telegramApi: FakeTelegramApi)(using
      repositories: RepositoryComponents
  ): DocumentTelegramNotifier =
    new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)(using
      executionContext
    )

  // A receipt from a Telegram chat whose bot message is already stored, classified as `documentClass`.
  private case class TelegramDocument(userId: UUID, documentId: UUID, receiptId: UUID, chatId: Long, messageId: Long)

  private def createTelegramDocument(documentClass: DocumentClass)(using
      repositories: RepositoryComponents
  ): TelegramDocument = {
    val user = createUser().futureValue
    val receipt = createReceipt(user.id).futureValue
    val telegramReceiptId = UUID.randomUUID()
    val chatId = RandomData.randomTelegramUserId()
    val messageId = RandomData.randomTelegramUserId()
    repositories.telegramReceipt.create(id = telegramReceiptId, receiptId = receipt.id, chatId = chatId).futureValue
    repositories.telegramReceipt.updateExpenseMessageId(id = telegramReceiptId, messageId = messageId).futureValue
    val classification = DocumentClassification(
      documentClass = documentClass,
      confidence = BigDecimal("0.97"),
      injectionSuspected = false,
      injectionConfidence = BigDecimal("0.01")
    )
    repositories.documents.updateClassification(receipt.documentId, user.id, classification).futureValue
    TelegramDocument(
      userId = user.id,
      documentId = receipt.documentId,
      receiptId = receipt.id,
      chatId = chatId,
      messageId = messageId
    )
  }

  "apply" should {
    "skip a bank statement without starting a Textract job" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue

      newAction()(doc.id).futureValue

      repositories.textractJobs.findByDocumentId(doc.id).futureValue mustBe empty
      repositories.receipts.findByDocumentId(doc.id).futureValue mustBe empty
    }

    "fail when a receipt document has no receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.Receipt).futureValue

      val error = newAction()(doc.id).failed.futureValue

      error.getMessage must be(s"Receipt not found for document ${doc.id}")
      repositories.textractJobs.findByDocumentId(doc.id).futureValue mustBe empty
    }

    "show the rejection on the stored Telegram message" in withRepositories() { implicit repositories =>
      val doc = createTelegramDocument(DocumentClass.BankVoucher)
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

      val receipt = repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value
      receipt.status must be(DocumentStatus.Unsupported)
      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = doc.chatId,
            messageId = doc.messageId,
            text = DocumentTelegramNotifier.notATicketMessage(DocumentClass.BankVoucher),
            button = None
          )
        )
      )
      telegramApi.sent must be(empty)
    }

    "show the reading stage on the stored Telegram message" in withRepositories() { implicit repositories =>
      val doc = createTelegramDocument(DocumentClass.PurchaseTicket)
      val sha256 = repositories.documents.findById(doc.documentId).futureValue.value.sha256
      val response = Json.obj("JobStatus" -> "SUCCEEDED")
      repositories.textractCache
        .create(TextractCache.Create(sha256, TextractApiType.AnalyzeExpense, response, Instant.now()))
        .futureValue
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

      val receipt = repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value
      receipt.status must be(DocumentStatus.Analyzed)
      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = doc.chatId,
            messageId = doc.messageId,
            text = "Reading the receipt...",
            button = None
          )
        )
      )
      telegramApi.sent must be(empty)
    }
  }
}
