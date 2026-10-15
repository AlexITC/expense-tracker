package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class TelegramReceiptRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.telegramReceipt.create(UUID.randomUUID(), receipt.id, chatId = 12345L).futureValue
    }

    "fail when the receipt doesn't exist" in withRepositories() { implicit repositories =>
      val ex = intercept[RuntimeException] {
        repositories.telegramReceipt.create(UUID.randomUUID(), UUID.randomUUID(), chatId = 12345L).futureValue
      }
      ex.getCause.getMessage must include("telegram_receipt_receipt_id_fk")
    }

    "fail on duplicate receipt_id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      repositories.telegramReceipt.create(UUID.randomUUID(), receipt.id, chatId = 12345L).futureValue

      val ex = intercept[RuntimeException] {
        repositories.telegramReceipt.create(UUID.randomUUID(), receipt.id, chatId = 99999L).futureValue
      }
      ex.getCause.getMessage must include("telegram_receipt_receipt_id_unique")
    }
  }

  "findByReceiptId" should {
    "return the record when it exists" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val id = UUID.randomUUID()
      val chatId = 55555L
      repositories.telegramReceipt.create(id, receipt.id, chatId).futureValue

      val result = repositories.telegramReceipt.findByReceiptId(receipt.id).futureValue.value
      result.telegramReceiptId must be(id)
      result.receiptId must be(receipt.id)
      result.chatId must be(chatId)
      result.expenseMessageId must be(None)
    }

    "return None when the receipt doesn't exist" in withRepositories() { repositories =>
      repositories.telegramReceipt.findByReceiptId(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "updateExpenseMessageId" should {
    "set the expense_message_id" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val receipt = createReceipt(user.id).futureValue
      val id = UUID.randomUUID()
      repositories.telegramReceipt.create(id, receipt.id, chatId = 77777L).futureValue

      repositories.telegramReceipt.updateExpenseMessageId(id, messageId = 42L).futureValue

      val result = repositories.telegramReceipt.findByReceiptId(receipt.id).futureValue.value
      result.expenseMessageId must be(Some(42L))
    }
  }
}
