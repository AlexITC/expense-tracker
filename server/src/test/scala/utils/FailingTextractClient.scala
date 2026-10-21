package utils

import net.wiringbits.apis.TextractClient
import play.api.libs.json.JsValue

import scala.concurrent.Future

/** Fake for tests whose code path must never reach Textract; any call fails the action under test. */
class FailingTextractClient extends TextractClient {
  private def unexpected[T](method: String): Future[T] =
    Future.failed(new RuntimeException(s"Unexpected Textract call: $method"))

  override def analyzeExpense(sha256: String, storageKey: String): Future[JsValue] = unexpected("analyzeExpense")

  override def startDocumentAnalysis(sha256: String, storageKey: String): Future[String] =
    unexpected("startDocumentAnalysis")

  override def getDocumentAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.AnalysisPage] =
    unexpected("getDocumentAnalysis")

  override def startExpenseAnalysis(sha256: String, storageKey: String): Future[String] =
    unexpected("startExpenseAnalysis")

  override def getExpenseAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.ExpensePage] =
    unexpected("getExpenseAnalysis")
}
