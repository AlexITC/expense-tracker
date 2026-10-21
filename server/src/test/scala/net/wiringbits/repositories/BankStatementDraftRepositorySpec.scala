package net.wiringbits.repositories

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatementDraft, Document}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.time.Instant
import java.util.UUID

class BankStatementDraftRepositorySpec extends RepositorySpec with RepositoryUtils {

  private val classification = DocumentClassification(
    documentClass = DocumentClass.BankStatement,
    confidence = BigDecimal("0.99"),
    injectionSuspected = false,
    injectionConfidence = BigDecimal("0.02")
  )

  private def buildDraft(userId: UUID, documentId: UUID, sha256: String): BankStatementDraft.Create = {
    val now = Instant.now()
    BankStatementDraft.Create(UUID.randomUUID(), userId, documentId, sha256, "stmt.pdf", "key", now, now)
  }

  private def buildJob(draftId: UUID): BackgroundJobData.Create = {
    val now = Instant.now()
    BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ExtractBankStatementDraft,
      payload = BackgroundJobPayload.ExtractBankStatementDraft(draftId),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
  }

  /** Create a draft backed by a fresh document (document_id is NOT NULL since the upload-creates-document change). */
  private def seedDraft(userId: UUID)(implicit repositories: RepositoryComponents): BankStatementDraft.Create = {
    val now = Instant.now()
    val document = Document.Create(
      id = UUID.randomUUID(),
      userId = userId,
      sha256 = "sha-" + UUID.randomUUID().toString,
      storageKey = "key",
      kind = DocumentKind.BankStatementPdf,
      filename = "stmt.pdf",
      createdAt = now,
      updatedAt = now
    )
    val draft = buildDraft(userId, document.id, document.sha256)
    repositories.bankStatementDrafts
      .create(BankStatementDraft.DocumentLink.New(document), draft, buildJob(draft.id))
      .futureValue
    draft
  }

  "updateClassification" should {
    "leave a new draft unclassified" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = seedDraft(user.id)

      val found = repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.value
      found.aiClassification must be(None)
      found.aiClassificationConfidence must be(None)
      found.aiInjectionSuspected must be(None)
      found.aiInjectionConfidence must be(None)
    }

    "store all the classification fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val draft = seedDraft(user.id)

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
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      val draft = seedDraft(user1.id)

      val error = repositories.bankStatementDrafts
        .updateClassification(draft.id, user2.id, classification, Instant.now())
        .failed
        .futureValue
      error.getMessage must include("No draft to update")
      repositories.bankStatementDrafts.findById(draft.id).futureValue.value.aiClassification must be(None)
    }
  }

  "create" should {
    "insert the new document and link the draft to it" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val now = Instant.now()
      val document = Document.Create(
        id = UUID.randomUUID(),
        userId = user.id,
        sha256 = "sha-new",
        storageKey = "key",
        kind = DocumentKind.BankStatementPdf,
        filename = "stmt.pdf",
        createdAt = now,
        updatedAt = now
      )
      val draft = buildDraft(user.id, document.id, document.sha256)
      repositories.bankStatementDrafts
        .create(BankStatementDraft.DocumentLink.New(document), draft, buildJob(draft.id))
        .futureValue

      repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.value.documentId must be(
        document.id
      )
      repositories.bankStatementDrafts.findById(draft.id).futureValue.value.documentId must be(document.id)
      repositories.bankStatementDrafts.listByUser(user.id).futureValue.map(_.documentId) must be(List(document.id))

      val stored = repositories.documents.findById(document.id).futureValue.value
      stored.kind must be(DocumentKind.BankStatementPdf)
    }

    "link the draft to an existing document without inserting another" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing =
        createDocument(user.id, sha256 = "sha-existing", kind = DocumentKind.BankStatementPdf).futureValue
      val draft = buildDraft(user.id, existing.id, existing.sha256)
      repositories.bankStatementDrafts
        .create(BankStatementDraft.DocumentLink.Existing(existing.id), draft, buildJob(draft.id))
        .futureValue

      repositories.bankStatementDrafts.findByIdForUser(draft.id, user.id).futureValue.value.documentId must be(
        existing.id
      )
      repositories.documents.all(user.id).futureValue.map(_.id) must be(List(existing.id))
    }
  }

  "delete" should {
    "keep the linked document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val existing = createDocument(user.id, kind = DocumentKind.BankStatementPdf).futureValue
      val draft = buildDraft(user.id, existing.id, existing.sha256)
      repositories.bankStatementDrafts
        .create(BankStatementDraft.DocumentLink.Existing(existing.id), draft, buildJob(draft.id))
        .futureValue

      repositories.bankStatementDrafts.delete(draft.id, user.id).futureValue must be(true)
      repositories.documents.findById(existing.id).futureValue must not be empty
    }
  }
}
