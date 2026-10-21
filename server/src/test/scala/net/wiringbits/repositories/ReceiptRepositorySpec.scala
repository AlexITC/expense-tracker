package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.DocumentStatus
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class ReceiptRepositorySpec extends RepositorySpec with RepositoryUtils {

  "createWithDocument" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createReceipt(user.id).futureValue
    }

    "store the receipt with the Ingested status" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.find(created.id, user.id).futureValue.value.status must be(DocumentStatus.Ingested)
    }
  }

  "updateStatus" should {
    "change the receipt status" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.updateStatus(created.id, user.id, DocumentStatus.Parsed).futureValue
      repositories.receipts.find(created.id, user.id).futureValue.value.status must be(DocumentStatus.Parsed)
    }

    "fail when the receipt belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createReceipt(userA.id).futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.updateStatus(created.id, userB.id, DocumentStatus.Parsed).futureValue
      }
      ex.getMessage must include("Receipt not found")
      repositories.receipts.find(created.id, userA.id).futureValue.value.status must be(DocumentStatus.Ingested)
    }

    "fail when the receipt does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val ex = intercept[RuntimeException] {
        repositories.receipts.updateStatus(UUID.randomUUID(), user.id, DocumentStatus.Parsed).futureValue
      }
      ex.getMessage must include("Receipt not found")
    }
  }

  "findByDocumentId" should {
    "return the receipt for an existing document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      val found = repositories.receipts.findByDocumentId(created.documentId).futureValue
      found.value.id must be(created.id)
    }

    "return None when no receipt exists for the document" in withRepositories() { implicit repositories =>
      repositories.receipts.findByDocumentId(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "find" should {
    "return the receipt when it belongs to the user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      val found = repositories.receipts.find(created.id, user.id).futureValue
      found.value.userId must be(user.id)
    }

    "return None when the receipt belongs to another user" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      val created = createReceipt(userA.id).futureValue

      repositories.receipts.find(created.id, userB.id).futureValue must be(None)
    }
  }

  "all" should {
    "return only the requesting user's receipts" in withRepositories() { implicit repositories =>
      val userA = createUser(email = net.wiringbits.common.models.Email.trusted("a@test.com")).futureValue
      val userB = createUser(email = net.wiringbits.common.models.Email.trusted("b@test.com")).futureValue
      createReceipt(userA.id).futureValue
      createReceipt(userA.id).futureValue
      createReceipt(userB.id).futureValue

      val results = repositories.receipts.all(userA.id).futureValue
      results.length must be(2)
    }

    "return an empty list when the user has no receipts" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      repositories.receipts.all(user.id).futureValue must be(empty)
    }
  }

  "ignoreReceipt / unignoreReceipt" should {
    "set and clear ignoredAt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.ignoreReceipt(created.id, user.id).futureValue
      val ignored = repositories.receipts.find(created.id, user.id).futureValue
      ignored.value.ignoredAt must be(defined)

      repositories.receipts.unignoreReceipt(created.id, user.id).futureValue
      val unignored = repositories.receipts.find(created.id, user.id).futureValue
      unignored.value.ignoredAt must be(None)
    }
  }

  "linkToExpense / unlinkExpense" should {
    "set and clear the expense link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val expense = createExpense(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.linkToExpense(created.id, user.id, expense.id).futureValue
      val linked = repositories.receipts.find(created.id, user.id).futureValue
      linked.value.expenseId must be(Some(expense.id))

      repositories.receipts.unlinkExpense(created.id, user.id).futureValue
      val unlinked = repositories.receipts.find(created.id, user.id).futureValue
      unlinked.value.expenseId must be(None)
    }
  }

  "linkToRefund / unlinkFromRefund" should {
    "set and clear the refund link" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val refund = createRefund(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.linkToRefund(created.id, user.id, refund.id).futureValue
      val linked = repositories.receipts.find(created.id, user.id).futureValue
      linked.value.refundId must be(Some(refund.id))

      repositories.receipts.unlinkFromRefund(created.id, user.id).futureValue
      val unlinked = repositories.receipts.find(created.id, user.id).futureValue
      unlinked.value.refundId must be(None)
    }
  }

  "setMerchantId" should {
    "associate a merchant with the receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val merchant = createMerchant(user.id).futureValue
      val created = createReceipt(user.id).futureValue

      repositories.receipts.setMerchantId(created.id, merchant.id).futureValue
      val found = repositories.receipts.find(created.id, user.id).futureValue
      found.value.merchantId must be(Some(merchant.id))
    }
  }
}
