package net.wiringbits.actions.documents

import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.DocumentKind
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import utils.{FailingTextractClient, RepositoryUtils}

import java.time.Clock

class PollReceiptActionSpec extends RepositorySpec with RepositoryUtils {

  private def newAction()(using repositories: RepositoryComponents): PollReceiptAction =
    new PollReceiptAction(
      repositories.documents,
      repositories.receipts,
      repositories.textractCache,
      repositories.textractJobs,
      repositories.backgroundJobs,
      new FailingTextractClient
    )(using executionContext, Clock.systemUTC())

  "apply" should {
    "fail when the document has no receipt" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc = createDocument(user.id, kind = DocumentKind.Receipt).futureValue

      val error = newAction()(doc.id).failed.futureValue

      error.getMessage must be(s"Receipt not found for document ${doc.id}")
    }
  }
}
