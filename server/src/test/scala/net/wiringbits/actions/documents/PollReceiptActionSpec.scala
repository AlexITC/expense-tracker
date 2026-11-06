package net.wiringbits.actions.documents

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.services.DocumentTelegramNotifier
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{FailingTextractClient, FakeTelegramApi, RandomData, RepositoryUtils}

import java.time.Clock
import java.util.UUID

class PollReceiptActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction(
      notifier: Option[DocumentTelegramNotifier] = None
  )(using repositories: RepositoryComponents): PollReceiptAction =
    new PollReceiptAction(
      repositories.documents,
      repositories.receipts,
      repositories.textractCache,
      repositories.textractJobs,
      repositories.backgroundJobs,
      new FailingTextractClient,
      notifier.getOrElse(documentTelegramNotifier)
    )(using executionContext, Clock.systemUTC())

  private def fakeNotifier(telegramApi: FakeTelegramApi)(using
      repositories: RepositoryComponents
  ): DocumentTelegramNotifier =
    new DocumentTelegramNotifier(repositories.receipts, repositories.telegramReceipt, telegramApi)(using
      executionContext
    )

  "apply" should {
    "fail when the document has no receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.Receipt).futureValue

      val error = newAction()(doc.id).failed.futureValue

      error.getMessage must be(s"Receipt not found for document ${doc.id}")
    }

    "show the failure on the stored Telegram message when no Textract job exists" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val receipt = createReceipt(user.id).futureValue
        val chatId = RandomData.randomTelegramUserId()
        val messageId = RandomData.randomTelegramUserId()
        repositories.telegramReceipt
          .create(id = UUID.randomUUID(), receiptId = receipt.id, chatId = chatId, expenseMessageId = Some(messageId))
          .futureValue
        val telegramApi = new FakeTelegramApi()(using executionContext)

        newAction(Some(fakeNotifier(telegramApi)))(receipt.documentId).futureValue

        repositories.receipts.find(receipt.id, user.id).futureValue.value.status must be(DocumentStatus.Unsupported)
        telegramApi.edited must be(
          List(
            FakeTelegramApi.Edited(
              chatId = chatId,
              messageId = messageId,
              text = "Could not read the receipt.",
              button = None
            )
          )
        )
        telegramApi.sent must be(empty)
    }
  }
}
