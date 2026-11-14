package net.wiringbits.actions.documents

import net.wiringbits.api.models.merchants.ImportRuleActionKind
import net.wiringbits.apis.{JevChoice, JevClient, JevRateLimitedException, TelegramApi, TextractApiType}
import net.wiringbits.config.{JevConfig, WebAppConfig}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.JevApiKey
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentStatus}
import net.wiringbits.repositories.DocumentsRepository
import net.wiringbits.repositories.models.TextractCache
import net.wiringbits.services.{DocumentClassifier, DocumentTelegramNotifier}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.{JsValue, Json}
import utils.{Executors, FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class ParseReceiptActionSpec extends RepositorySpec with RepositoryUtils {

  private val minimalTextractJson =
    Json.parse(
      """{
        |  "ExpenseDocuments": [
        |    {
        |      "SummaryFields": [],
        |      "LineItemGroups": []
        |    }
        |  ]
        |}""".stripMargin
    )

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

  private val readyText = "Receipt ready: EXAMPLE BANK · 12.34 EUR"

  // A receipt from a Telegram chat whose bot message is already stored.
  private case class TelegramReceiptDoc(
      userId: UUID,
      documentId: UUID,
      receiptId: UUID,
      chatId: Long,
      messageId: Long
  )

  private def createTelegramReceipt(
      textractResponse: JsValue = voucherTextractJson
  )(using repositories: RepositoryComponents): TelegramReceiptDoc = {
    val user = createUser().futureValue
    val receipt = createReceipt(user.id).futureValue
    val chatId = RandomData.randomTelegramUserId()
    val messageId = RandomData.randomTelegramUserId()
    repositories.telegramReceipt
      .create(id = UUID.randomUUID(), receiptId = receipt.id, chatId = chatId, expenseMessageId = Some(messageId))
      .futureValue
    val sha256 = repositories.documents.findById(receipt.documentId).futureValue.value.sha256
    repositories.textractCache
      .create(TextractCache.Create(sha256, TextractApiType.AnalyzeExpense, textractResponse, Instant.now()))
      .futureValue
    TelegramReceiptDoc(
      userId = user.id,
      documentId = receipt.documentId,
      receiptId = receipt.id,
      chatId = chatId,
      messageId = messageId
    )
  }

  private def newAction(
      classifier: DocumentClassifier,
      telegramApi: FakeTelegramApi,
      documentsRepository: Option[DocumentsRepository] = None
  )(using repositories: RepositoryComponents): ParseReceiptAction =
    new ParseReceiptAction(
      documentsRepository.getOrElse(repositories.documents),
      repositories.receipts,
      repositories.merchants,
      repositories.importRules,
      repositories.textractCache,
      webAppConfig,
      classifier,
      new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)(using
        executionContext
      )
    )(using executionContext, Clock.systemUTC())

  private def storedClassification(doc: TelegramReceiptDoc)(using
      repositories: RepositoryComponents
  ): Option[DocumentClassification] =
    repositories.documents.findById(doc.documentId).futureValue.value.classification

  // The receipt is parsed and its Telegram message shows `text` with the Create Expense button.
  private def verifyParsed(doc: TelegramReceiptDoc, telegramApi: FakeTelegramApi, text: String = readyText)(using
      repositories: RepositoryComponents
  ): Unit = {
    repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.status must be(DocumentStatus.Parsed)
    telegramApi.edited must be(
      List(
        FakeTelegramApi.Edited(
          chatId = doc.chatId,
          messageId = doc.messageId,
          text = text,
          button = Some(
            TelegramApi.WebAppButton(
              label = "Create Expense",
              url = s"${webAppConfig.host}/api/telegram/webapp?receipt=${doc.receiptId}"
            )
          )
        )
      )
    )
    telegramApi.sent must be(empty)
  }

  "apply (advisory classification)" should {
    "persist the classification and still parse a document that is not a ticket, advising the user" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt()
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(realClassifier(stubJev("bank_voucher")), telegramApi)(doc.documentId).futureValue

        storedClassification(doc) must be(
          Some(DocumentClassification(DocumentClass.BankVoucher, BigDecimal("1.0"), false, BigDecimal("0.99")))
        )
        verifyParsed(
          doc,
          telegramApi,
          text = DocumentTelegramNotifier.notATicketMessage(DocumentClass.BankVoucher) + "\n" + readyText
        )
    }

    "persist a purchase ticket classification without advising the user" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt()
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(realClassifier(stubJev("purchase_ticket")), telegramApi)(doc.documentId).futureValue

        storedClassification(doc) must be(
          Some(DocumentClassification(DocumentClass.PurchaseTicket, BigDecimal("1.0"), false, BigDecimal("0.99")))
        )
        verifyParsed(doc, telegramApi)
    }

    "not classify a document that was already classified" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt()
      val stored = DocumentClassification(DocumentClass.PurchaseTicket, BigDecimal("0.95"), false, BigDecimal("0.99"))
      repositories.documents.updateClassification(doc.documentId, doc.userId, stored).futureValue
      val telegramApi = new FakeTelegramApi()(using executionContext)
      val jev = stubJev("bank_voucher")

      newAction(realClassifier(jev), telegramApi)(doc.documentId).futureValue

      jev.calls.get() mustBe 0
      storedClassification(doc) must be(Some(stored))
      verifyParsed(doc, telegramApi)
    }

    "parse normally without persisting anything when JEV fails (the classifier answers inconclusive)" in withRepositories() {
      implicit repositories =>
        val doc = createTelegramReceipt()
        val telegramApi = new FakeTelegramApi()(using executionContext)
        val jev = failingJev

        newAction(realClassifier(jev), telegramApi)(doc.documentId).futureValue

        jev.calls.get() mustBe 1
        storedClassification(doc) mustBe empty
        verifyParsed(doc, telegramApi)
    }

    "still parse when storing the classification fails" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt()
      val telegramApi = new FakeTelegramApi()(using executionContext)
      val failingStore = new DocumentsRepository(repositories.database)(using Executors.databaseEC, Clock.systemUTC()) {
        override def updateClassification(id: UUID, userId: UUID, classification: DocumentClassification) =
          Future.failed(new RuntimeException("db down"))
      }

      newAction(realClassifier(stubJev("bank_voucher")), telegramApi, documentsRepository = Some(failingStore))(
        doc.documentId
      ).futureValue

      storedClassification(doc) mustBe empty
      verifyParsed(doc, telegramApi)
    }

    "still mark the receipt Parsed when the Telegram edit fails" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt()
      val telegramApi =
        new FakeTelegramApi(editError = Some(FakeTelegramApi.rateLimitedError))(using executionContext)

      newAction(realClassifier(stubJev("bank_voucher")), telegramApi)(doc.documentId).futureValue

      storedClassification(doc) must not be empty
      verifyParsed(
        doc,
        telegramApi,
        text = DocumentTelegramNotifier.notATicketMessage(DocumentClass.BankVoucher) + "\n" + readyText
      )
    }

    "not classify a document whose Textract response has no text" in withRepositories() { implicit repositories =>
      val doc = createTelegramReceipt(textractResponse = minimalTextractJson)
      val telegramApi = new FakeTelegramApi()(using executionContext)
      val jev = stubJev("other")

      newAction(realClassifier(jev), telegramApi)(doc.documentId).futureValue

      jev.calls.get() mustBe 0
      storedClassification(doc) mustBe empty
      verifyParsed(doc, telegramApi, text = "Receipt ready.")
    }
  }

  "apply (import rules with an amount)" should {
    // The voucher's vendor is EXAMPLE BANK and its total 12.34; rule amounts are charges, so negative.
    def parseWithRuleAmount(ruleAmount: Option[BigDecimal], textract: JsValue = voucherTextractJson)(using
        repositories: RepositoryComponents
    ): (UUID, Option[UUID]) = {
      val doc = createTelegramReceipt(textract)
      val merchant = createMerchant(doc.userId).futureValue
      createImportRule(
        doc.userId,
        pattern = "example bank",
        amount = ruleAmount,
        action = ImportRuleActionKind.SetMerchant,
        merchantId = Some(merchant.id)
      ).futureValue
      val telegramApi = new FakeTelegramApi()(using executionContext)

      newAction(realClassifier(stubJev("purchase_ticket")), telegramApi)(doc.documentId).futureValue

      (merchant.id, repositories.receipts.find(doc.receiptId, doc.userId).futureValue.value.merchantId)
    }

    "set the merchant when the receipt total equals the rule amount" in withRepositories() { implicit repositories =>
      val (merchantId, assigned) = parseWithRuleAmount(Some(BigDecimal("-12.34")))
      assigned must be(Some(merchantId))
    }

    "not use the rule's merchant when the receipt total differs" in withRepositories() { implicit repositories =>
      val (merchantId, assigned) = parseWithRuleAmount(Some(BigDecimal("-12.35")))
      // No rule matched, so the receipt gets a pending merchant named after the vendor instead.
      assigned must not be empty
      assigned must not be Some(merchantId)
    }

    "treat a parsed total of 0 as unknown: amount rules do not apply, amount-less rules do" in withRepositories() {
      implicit repositories =>
        val zeroTotal = Json.parse(Json.stringify(voucherTextractJson).replace("12.34", "0.00"))
        // The API rejects a zero rule amount, so any amount rule shows the 0 total never matches.
        val (amountRuleMerchant, assigned) = parseWithRuleAmount(Some(BigDecimal("-12.34")), zeroTotal)
        assigned must not be Some(amountRuleMerchant)

        val (plainRuleMerchant, assignedByPlain) = parseWithRuleAmount(None, zeroTotal)
        assignedByPlain must be(Some(plainRuleMerchant))
    }
  }
}
