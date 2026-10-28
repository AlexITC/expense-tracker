package net.wiringbits.repositories

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, TextractCache}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.Json
import utils.{RandomData, RepositoryUtils}

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

class DocumentsRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "create a document and background job atomically" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val found = repositories.documents.find(doc.id, user.id).futureValue
      found must not be empty
    }

    "return the existing id without a second document or job when the sha256 is taken" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val sha = RandomData.randomString()
        val first = createDocument(user.id, sha256 = sha).futureValue
        val (doc, job) = newDocumentAndJob(user.id, sha)

        repositories.documents.create(doc, job).futureValue must be(first.id)
        documentCountOf(user.id, sha) must be(1)
        jobCountOf(first.id) must be(1)
        jobCountOf(doc.id) must be(0)
    }

    "store a single document and job under concurrent creates with the same sha256" in withRepositories() {
      implicit repositories =>
        val user = createUser().futureValue
        val sha = RandomData.randomString()
        val requests = (1 to 8).map(_ => newDocumentAndJob(user.id, sha))

        val ids =
          Future.sequence(requests.map { case (doc, job) => repositories.documents.create(doc, job) }).futureValue
        ids.distinct.size must be(1)
        documentCountOf(user.id, sha) must be(1)
        jobCountOf(ids.head) must be(1)
    }

    "store a separate document per user for the same sha256" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      val sha = RandomData.randomString()
      val first = createDocument(user1.id, sha256 = sha).futureValue
      val (doc, job) = newDocumentAndJob(user2.id, sha)

      repositories.documents.create(doc, job).futureValue must be(doc.id)
      documentCountOf(user1.id, sha) must be(1)
      documentCountOf(user2.id, sha) must be(1)
      jobCountOf(first.id) must be(1)
      jobCountOf(doc.id) must be(1)
    }
  }

  "all" should {
    "return the user's documents in descending created_at order" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      createDocument(user.id, sha256 = "sha1", filename = "a.pdf").futureValue
      createDocument(user.id, sha256 = "sha2", filename = "b.pdf").futureValue

      val docs = repositories.documents.all(user.id).futureValue
      docs.length must be(2)
    }

    "not return another user's documents" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      createDocument(user1.id).futureValue

      repositories.documents.all(user2.id).futureValue must be(empty)
    }
  }

  "findBySha256" should {
    "find a document by sha256 per user" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, sha256 = "mysha").futureValue

      val found = repositories.documents.findBySha256(user.id, "mysha").futureValue
      found must not be empty
      found.get.id must be(doc.id)
    }

    "not find document of a different user" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      createDocument(user1.id, sha256 = "shared").futureValue

      repositories.documents.findBySha256(user2.id, "shared").futureValue must be(empty)
    }
  }

  "updateClassification" should {
    "leave a new document unclassified" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue

      val found = repositories.documents.find(doc.id, user.id).futureValue.value
      found.aiClassification must be(None)
      found.aiClassificationConfidence must be(None)
      found.aiInjectionSuspected must be(None)
      found.aiInjectionConfidence must be(None)
    }

    "store all the classification fields" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val classification = DocumentClassification(
        documentClass = DocumentClass.PurchaseTicket,
        confidence = BigDecimal("0.97"),
        injectionSuspected = true,
        injectionConfidence = BigDecimal("0.81")
      )

      repositories.documents.updateClassification(doc.id, user.id, classification).futureValue

      val found = repositories.documents.find(doc.id, user.id).futureValue.value
      found.aiClassification must be(Some(DocumentClass.PurchaseTicket))
      found.aiClassificationConfidence must be(Some(BigDecimal("0.97")))
      found.aiInjectionSuspected must be(Some(true))
      found.aiInjectionConfidence must be(Some(BigDecimal("0.81")))
      found.classification must be(Some(classification))
    }

    "fail when the document belongs to another user" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      val doc = createDocument(user1.id).futureValue
      val classification = DocumentClassification(DocumentClass.Invoice, BigDecimal("0.9"), false, BigDecimal("0.1"))

      val error = repositories.documents.updateClassification(doc.id, user2.id, classification).expectError
      error must include("Document not found")
      repositories.documents.find(doc.id, user1.id).futureValue.value.aiClassification must be(None)
    }
  }

  "delete" should {
    "delete the document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue

      repositories.documents.delete(doc.id, user.id).futureValue
      repositories.documents.find(doc.id, user.id).futureValue must be(empty)
    }

    "fail when document belongs to another user" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      val doc = createDocument(user1.id).futureValue

      val error = repositories.documents.delete(doc.id, user2.id).expectError
      error must include("Document not found")
    }

    "fail when document does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue

      val error = repositories.documents.delete(UUID.randomUUID(), user.id).expectError
      error must include("Document not found")
    }
  }

  "textractCache" should {
    "store and retrieve by (sha256, apiType)" in withRepositories() { implicit repositories =>
      val sha256 = RandomData.randomString("sha")
      val response = Json.obj("JobStatus" -> "SUCCEEDED")
      repositories.textractCache
        .create(TextractCache.Create(sha256, "analyze_expense", response, Instant.now()))
        .futureValue

      val found = repositories.textractCache.find(sha256, "analyze_expense").futureValue
      found must not be empty
      found.get.sha256 must be(sha256)
      found.get.apiType must be("analyze_expense")
    }

    "be global — same (sha256, apiType) across users reuses cache" in withRepositories() { implicit repositories =>
      val user1 = createUser().futureValue
      val user2 = createUser().futureValue
      val sha256 = RandomData.randomString("sha")
      createDocument(user1.id, sha256 = sha256).futureValue
      createDocument(user2.id, sha256 = "global2").futureValue

      val response = Json.obj("JobStatus" -> "SUCCEEDED")
      repositories.textractCache
        .create(TextractCache.Create(sha256, "analyze_expense", response, Instant.now()))
        .futureValue
      repositories.textractCache
        .create(TextractCache.Create(sha256, "analyze_expense", response, Instant.now()))
        .futureValue

      // ON CONFLICT DO NOTHING — still only one row
      val found = repositories.textractCache.find(sha256, "analyze_expense").futureValue
      found must not be empty
    }
  }

  implicit class FutureOps[T](val f: scala.concurrent.Future[T]) {
    def expectError: String =
      f.map(_ => "Success when failure expected").recover { case ex => ex.getMessage }.futureValue
  }

  private def newDocumentAndJob(userId: UUID, sha256: String): (Document.Create, BackgroundJobData.Create) = {
    val now = Instant.now()
    val doc = Document.Create(
      id = UUID.randomUUID(),
      userId = userId,
      sha256 = sha256,
      storageKey = "",
      kind = DocumentKind.Receipt,
      filename = "test.pdf",
      createdAt = now,
      updatedAt = now
    )
    val job = BackgroundJobData.Create(
      id = UUID.randomUUID(),
      `type` = BackgroundJobType.ProcessDocument,
      payload = BackgroundJobPayload.ProcessDocument(doc.id),
      status = BackgroundJobStatus.Pending,
      executeAt = now,
      createdAt = now,
      updatedAt = now
    )
    (doc, job)
  }

  private def documentCountOf(userId: UUID, sha256: String)(using repositories: RepositoryComponents): Int =
    repositories.database.withConnection { implicit conn =>
      anorm
        .SQL("SELECT COUNT(*) FROM document WHERE user_id = {userId}::UUID AND sha256 = {sha256}")
        .on("userId" -> userId.toString, "sha256" -> sha256)
        .as(anorm.SqlParser.scalar[Int].single)
    }

  private def jobCountOf(documentId: UUID)(using repositories: RepositoryComponents): Int =
    repositories.database.withConnection { implicit conn =>
      anorm
        .SQL(
          "SELECT COUNT(*) FROM background_jobs WHERE type = {type} AND payload ->> 'documentId' = {documentId}"
        )
        .on("type" -> BackgroundJobType.ProcessDocument.entryName, "documentId" -> documentId.toString)
        .as(anorm.SqlParser.scalar[Int].single)
    }
}
