package net.wiringbits.actions.documents

import net.wiringbits.apis.TelegramApi
import net.wiringbits.config.{TelegramConfig, WebAppConfig}
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.inject.DefaultApplicationLifecycle
import utils.RepositoryUtils

import java.time.Clock

class ParseReceiptActionStatusSpec extends RepositorySpec with RepositoryUtils {

  // These paths never reach Telegram; the real client is built with placeholder config and is never called.
  private val telegramApi = new TelegramApi(
    TelegramConfig(enabled = false, botToken = "test-token", webhookSecret = "test-secret", botName = "test"),
    new DefaultApplicationLifecycle
  )(using executionContext)

  private def newAction()(using repositories: RepositoryComponents): ParseReceiptAction =
    new ParseReceiptAction(
      repositories.documents,
      repositories.receipts,
      repositories.merchants,
      repositories.importRules,
      repositories.textractCache,
      repositories.telegramReceipt,
      telegramApi,
      WebAppConfig("https://app.example.com")
    )(using executionContext, Clock.systemUTC())

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
  }
}
