package net.wiringbits.actions.documents

import net.wiringbits.apis.{TelegramApi, TextractApiType}
import net.wiringbits.config.WebAppConfig
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.repositories.models.TextractCache
import net.wiringbits.services.DocumentTelegramNotifier
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.{JsValue, Json}
import utils.{FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.{Clock, Instant}
import java.util.UUID

class ParseReceiptActionStatusSpec extends RepositorySpec with RepositoryUtils {

  private val webAppConfig = WebAppConfig("https://app.example.com")

  private def newAction(
      notifier: Option[DocumentTelegramNotifier] = None
  )(using repositories: RepositoryComponents): ParseReceiptAction =
    new ParseReceiptAction(
      repositories.documents,
      repositories.receipts,
      repositories.merchants,
      repositories.importRules,
      repositories.textractCache,
      webAppConfig,
      mockDocumentClassifier,
      notifier.getOrElse(documentTelegramNotifier)
    )(using executionContext, Clock.systemUTC())

  private def fakeNotifier(telegramApi: FakeTelegramApi)(using
      repositories: RepositoryComponents
  ): DocumentTelegramNotifier =
    new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)(using
      executionContext
    )

  // A receipt from a Telegram chat whose bot message is already stored.
  private case class TelegramReceiptDoc(
      userId: UUID,
      documentId: UUID,
      receiptId: UUID,
      sha256: String,
      chatId: Long,
      messageId: Long
  )

  private def createTelegramReceipt(
      kind: DocumentKind = DocumentKind.Receipt
  )(using repositories: RepositoryComponents): TelegramReceiptDoc = {
    val user = createUser().futureValue
    val receipt = createReceipt(user.id, kind = kind).futureValue
    val telegramReceiptId = UUID.randomUUID()
    val chatId = RandomData.randomTelegramUserId()
    val messageId = RandomData.randomTelegramUserId()
    repositories.telegramReceipt.create(id = telegramReceiptId, receiptId = receipt.id, chatId = chatId).futureValue
    repositories.telegramReceipt.updateExpenseMessageId(id = telegramReceiptId, messageId = messageId).futureValue
    TelegramReceiptDoc(
      userId = user.id,
      documentId = receipt.documentId,
      receiptId = receipt.id,
      sha256 = repositories.documents.findById(receipt.documentId).futureValue.value.sha256,
      chatId = chatId,
      messageId = messageId
    )
  }

  private def cacheTextract(sha256: String, response: JsValue)(using repositories: RepositoryComponents): Unit =
    repositories.textractCache
      .create(TextractCache.Create(sha256, TextractApiType.AnalyzeExpense, response, Instant.now()))
      .futureValue

  private def createExpenseButton(receiptId: UUID): Option[TelegramApi.WebAppButton] =
    Some(
      TelegramApi.WebAppButton(
        label = "Create Expense",
        url = s"${webAppConfig.host}/api/telegram/webapp?receipt=$receiptId"
      )
    )

  // Synthetic data only.
  private val fullTextractJson = Json.parse(
    """{
      |  "ExpenseDocuments": [
      |    {
      |      "SummaryFields": [
      |        {
      |          "Type": { "Text": "VENDOR_NAME", "Confidence": 99.0 },
      |          "ValueDetection": { "Text": "Starbucks", "Confidence": 98.0 }
      |        },
      |        {
      |          "Type": { "Text": "TOTAL", "Confidence": 99.0 },
      |          "ValueDetection": { "Text": "12.34", "Confidence": 97.0 },
      |          "Currency": { "Code": "USD" }
      |        },
      |        {
      |          "Type": { "Text": "INVOICE_RECEIPT_DATE", "Confidence": 99.0 },
      |          "ValueDetection": { "Text": "15/01/2026", "Confidence": 97.0 }
      |        }
      |      ],
      |      "LineItemGroups": [
      |        {
      |          "LineItems": [
      |            {
      |              "LineItemExpenseFields": [
      |                {
      |                  "Type": { "Text": "ITEM" },
      |                  "ValueDetection": { "Text": "Coffee" }
      |                },
      |                {
      |                  "Type": { "Text": "PRICE" },
      |                  "ValueDetection": { "Text": "4.00" }
      |                }
      |              ]
      |            },
      |            {
      |              "LineItemExpenseFields": [
      |                {
      |                  "Type": { "Text": "ITEM" },
      |                  "ValueDetection": { "Text": "Muffin" }
      |                },
      |                {
      |                  "Type": { "Text": "PRICE" },
      |                  "ValueDetection": { "Text": "3.34" }
      |                }
      |              ]
      |            },
      |            {
      |              "LineItemExpenseFields": [
      |                {
      |                  "Type": { "Text": "ITEM" },
      |                  "ValueDetection": { "Text": "Juice" }
      |                },
      |                {
      |                  "Type": { "Text": "PRICE" },
      |                  "ValueDetection": { "Text": "5.00" }
      |                }
      |              ]
      |            }
      |          ]
      |        }
      |      ]
      |    }
      |  ]
      |}""".stripMargin
  )

  // Only a total is readable: no vendor, date or line items.
  private val totalOnlyTextractJson = Json.parse(
    """{
      |  "ExpenseDocuments": [
      |    {
      |      "SummaryFields": [
      |        {
      |          "Type": { "Text": "TOTAL", "Confidence": 99.0 },
      |          "ValueDetection": { "Text": "7.50", "Confidence": 97.0 },
      |          "Currency": { "Code": "USD" }
      |        }
      |      ],
      |      "LineItemGroups": []
      |    }
      |  ]
      |}""".stripMargin
  )

  // A single line item and nothing else readable.
  private val singleItemTextractJson = Json.parse(
    """{
      |  "ExpenseDocuments": [
      |    {
      |      "SummaryFields": [],
      |      "LineItemGroups": [
      |        {
      |          "LineItems": [
      |            {
      |              "LineItemExpenseFields": [
      |                {
      |                  "Type": { "Text": "ITEM" },
      |                  "ValueDetection": { "Text": "Coffee" }
      |                }
      |              ]
      |            }
      |          ]
      |        }
      |      ]
      |    }
      |  ]
      |}""".stripMargin
  )

  // Nothing readable: the parser reports a zero total, which must not be shown.
  private val emptyTextractJson = Json.parse(
    """{
      |  "ExpenseDocuments": [
      |    {
      |      "SummaryFields": [],
      |      "LineItemGroups": []
      |    }
      |  ]
      |}""".stripMargin
  )

  private def assertReadyText(textractJson: JsValue, expected: String)(using
      repositories: RepositoryComponents
  ): Unit = {
    val doc = createTelegramReceipt()
    cacheTextract(doc.sha256, textractJson)
    val telegramApi = new FakeTelegramApi()(using executionContext)

    newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

    telegramApi.edited must be(
      List(
        FakeTelegramApi.Edited(
          chatId = doc.chatId,
          messageId = doc.messageId,
          text = expected,
          button = createExpenseButton(doc.receiptId)
        )
      )
    )
    telegramApi.sent must be(empty)
  }

  "apply" should {
    "skip a receipt that is already Parsed" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.receipts.updateStatus(receipt.id, user.id, DocumentStatus.Parsed).futureValue

      // No Textract cache exists, so a non-skipped parse would mark the receipt Unsupported.
      newAction()(receipt.documentId).futureValue

      repositories.receipts.find(receipt.id, user.id).futureValue.value.status must be(DocumentStatus.Parsed)
    }

    "fail when the document has no receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.Receipt).futureValue

      val error = newAction()(doc.id).failed.futureValue

      error.getMessage must be(s"Receipt not found for document ${doc.id}")
    }

    "show the parsed summary with the Create Expense button on the stored Telegram message" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt()
        cacheTextract(doc.sha256, fullTextractJson)
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

        repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.status must be(DocumentStatus.Parsed)
        telegramApi.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = doc.chatId,
              messageId = doc.messageId,
              text = "Receipt ready: Starbucks · 12.34 USD · 2026-01-15 · 3 items",
              button = createExpenseButton(doc.receiptId)
            )
          )
        )
        telegramApi.sent must be(empty)
    }

    "leave fields the parser did not find out of the summary" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt()
      cacheTextract(doc.sha256, totalOnlyTextractJson)
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

      telegramApi.edited must be(
        List(
          FakeTelegramApi.Edited(
            chatId = doc.chatId,
            messageId = doc.messageId,
            text = "Receipt ready: 7.50 USD",
            button = createExpenseButton(doc.receiptId)
          )
        )
      )
      telegramApi.sent must be(empty)
    }

    "use the singular for a single line item" in withRepositories() { implicit repositories =>
      assertReadyText(singleItemTextractJson, "Receipt ready: 1 item")
    }

    "show only the ready text when no field was read, leaving out the zero total" in withRepositories() {
      implicit repositories =>
        assertReadyText(emptyTextractJson, "Receipt ready.")
    }

    "show the failure on the stored Telegram message when the Textract cache is missing" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt()
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

        repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.status must be(
          DocumentStatus.Unsupported
        )
        telegramApi.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = doc.chatId,
              messageId = doc.messageId,
              text = "Could not parse the receipt: analysis data not found.",
              button = None
            )
          )
        )
        telegramApi.sent must be(empty)
    }

    "show the failure on the stored Telegram message when the document kind is unsupported" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt(kind = DocumentKind.BankStatementPdf)
        cacheTextract(doc.sha256, fullTextractJson)
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(Some(fakeNotifier(telegramApi)))(doc.documentId).futureValue

        repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.status must be(
          DocumentStatus.Unsupported
        )
        telegramApi.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = doc.chatId,
              messageId = doc.messageId,
              text = "Could not parse the receipt: unsupported document format.",
              button = None
            )
          )
        )
        telegramApi.sent must be(empty)
    }

    "not call Telegram when the receipt did not come from Telegram" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val sha256 = repositories.documents.findById(receipt.documentId).futureValue.value.sha256
      cacheTextract(sha256, fullTextractJson)
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newAction(Some(fakeNotifier(telegramApi)))(receipt.documentId).futureValue

      repositories.receipts.find(receipt.id, user.id).futureValue.value.status must be(DocumentStatus.Parsed)
      telegramApi.edited must be(empty)
      telegramApi.sent must be(empty)
    }
  }
}
