package net.wiringbits.repositories

import net.wiringbits.common.models.Email
import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant

class BankStatementDraftRepositorySpec extends RepositorySpec with RepositoryUtils {

  private val classification = DocumentClassification(
    documentClass = DocumentClass.BankStatement,
    confidence = BigDecimal("0.99"),
    injectionSuspected = false,
    injectionConfidence = BigDecimal("0.02")
  )

  "updateClassification" should {
    "leave a new draft unclassified" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = createBankStatementDraft(user.id).futureValue

      val found = repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.value
      found.aiClassification must be(None)
      found.aiClassificationConfidence must be(None)
      found.aiInjectionSuspected must be(None)
      found.aiInjectionConfidence must be(None)
    }

    "store all the classification fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = createBankStatementDraft(user.id).futureValue

      repositories.bankStatementDrafts
        .updateClassification(draft.id, user.id, classification, Instant.now())
        .futureValue

      val found = repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.value
      found.aiClassification must be(Some(DocumentClass.BankStatement))
      found.aiClassificationConfidence must be(Some(BigDecimal("0.99")))
      found.aiInjectionSuspected must be(Some(false))
      found.aiInjectionConfidence must be(Some(BigDecimal("0.02")))
      found.classification must be(Some(classification))
    }

    "fail when the draft belongs to another user" in withRepositories() { implicit repositories =>
      val user1 = createUser(email = Email.trusted("u1@test.com")).futureValue
      val user2 = createUser(email = Email.trusted("u2@test.com")).futureValue
      val draft = createBankStatementDraft(user1.id).futureValue

      val error = repositories.bankStatementDrafts
        .updateClassification(draft.id, user2.id, classification, Instant.now())
        .failed
        .futureValue
      error.getMessage must include("No draft to update")
      repositories.bankStatementDrafts.findById(draft.id).futureValue.value.aiClassification must be(None)
    }
  }
}
