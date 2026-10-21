package net.wiringbits.actions.documents

import anorm.*
import net.wiringbits.apis.TextractClient
import net.wiringbits.core.{RepositoryComponents, RepositorySpec}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import org.scalatest.OptionValues.*
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.JsValue
import utils.RepositoryUtils

import java.time.Clock
import scala.concurrent.Future

class ProcessDocumentActionSpec extends RepositorySpec with RepositoryUtils {

  private object FailingTextractClient extends TextractClient {
    private def fail[T]: Future[T] = Future.failed(new RuntimeException("Textract must not be called"))
    override def analyzeExpense(sha256: String, storageKey: String): Future[JsValue] = fail
    override def startDocumentAnalysis(sha256: String, storageKey: String): Future[String] = fail
    override def getDocumentAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.AnalysisPage] =
      fail
    override def startExpenseAnalysis(sha256: String, storageKey: String): Future[String] = fail
    override def getExpenseAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.ExpensePage] =
      fail
  }

  private def newAction(repositories: RepositoryComponents): ProcessDocumentAction =
    new ProcessDocumentAction(
      repositories.documents,
      repositories.textractCache,
      repositories.textractJobs,
      repositories.backgroundJobs,
      FailingTextractClient
    )(using executionContext, Clock.systemUTC())

  // No repository API reads background jobs back, so count the stored rows directly.
  private def backgroundJobCount(repositories: RepositoryComponents): Long =
    repositories.database.withConnection { implicit conn =>
      SQL"SELECT COUNT(*) FROM background_jobs".as(SqlParser.scalar[Long].single)
    }

  "apply" should {
    "skip Textract for a bank statement document" in withRepositories() { implicit repositories =>
      val user = createUser().futureValue
      val doc =
        createDocument(user.id, kind = DocumentKind.BankStatementPdf, status = DocumentStatus.Ingested).futureValue
      val jobsBefore = backgroundJobCount(repositories)

      newAction(repositories)(doc.id).futureValue

      repositories.documents.findById(doc.id).futureValue.value.status must be(DocumentStatus.Ingested)
      repositories.textractJobs.findByDocumentId(doc.id).futureValue must be(None)
      backgroundJobCount(repositories) must be(jobsBefore)
    }
  }
}
