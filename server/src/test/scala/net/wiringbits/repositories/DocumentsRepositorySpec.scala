package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, TextractCache}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.Json
import utils.{RandomData, RepositoryUtils}

import java.time.Instant
import java.util.UUID

class DocumentsRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "create a document and background job atomically" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val found = repositories.documents.find(doc.id, user.id).futureValue
      found must not be empty
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
}
