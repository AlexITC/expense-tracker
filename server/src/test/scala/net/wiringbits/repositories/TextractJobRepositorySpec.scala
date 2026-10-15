package net.wiringbits.repositories

import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.repositories.models.TextractJob
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.RepositoryUtils

import java.util.UUID

class TextractJobRepositorySpec extends RepositorySpec with RepositoryUtils {

  "create" should {
    "work" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.Receipt).futureValue
      createTextractJob(user.id, doc.id).futureValue
    }

    "fail when the document does not exist" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val ex = intercept[RuntimeException] {
        createTextractJob(user.id, UUID.randomUUID()).futureValue
      }
      ex.getCause.getMessage must include("textract_job_document_id_fk")
    }
  }

  "findByDocumentId" should {
    "return the job for an existing document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val created = createTextractJob(user.id, doc.id).futureValue

      val found = repositories.textractJobs.findByDocumentId(doc.id).futureValue
      found.value.id must be(created.id)
      found.value.status must be(TextractJob.Status.PendingStart)
      found.value.attempts must be(0)
    }

    "return None when no job exists for the document" in withRepositories() { implicit repositories =>
      repositories.textractJobs.findByDocumentId(UUID.randomUUID()).futureValue must be(None)
    }
  }

  "incrementAttempts" should {
    "increment the attempt counter" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val created = createTextractJob(user.id, doc.id).futureValue

      repositories.textractJobs.incrementAttempts(created.id).futureValue
      repositories.textractJobs.incrementAttempts(created.id).futureValue

      val job = repositories.textractJobs.findByDocumentId(doc.id).futureValue
      job.value.attempts must be(2)
    }
  }

  "markSucceeded" should {
    "set the status to Succeeded" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val created = createTextractJob(user.id, doc.id).futureValue

      repositories.textractJobs.markSucceeded(created.id).futureValue

      val job = repositories.textractJobs.findByDocumentId(doc.id).futureValue
      job.value.status must be(TextractJob.Status.Succeeded)
    }
  }

  "markFailed" should {
    "set the status to Failed" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id).futureValue
      val created = createTextractJob(user.id, doc.id).futureValue

      repositories.textractJobs.markFailed(created.id).futureValue

      val job = repositories.textractJobs.findByDocumentId(doc.id).futureValue
      job.value.status must be(TextractJob.Status.Failed)
    }
  }
}
