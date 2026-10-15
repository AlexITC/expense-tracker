package net.wiringbits.apis

import play.api.libs.json.JsValue

import scala.concurrent.Future

trait TextractClient {
  def analyzeExpense(sha256: String, storageKey: String): Future[JsValue]

  /** Start an async AnalyzeDocument job. Returns the Textract JobId. */
  def startDocumentAnalysis(sha256: String, storageKey: String): Future[String]

  /** Fetch one page of a GetDocumentAnalysis result. Returns (JobStatus, Blocks, Option[NextToken]). */
  def getDocumentAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.AnalysisPage]

  /** Start an async StartExpenseAnalysis job. Returns the Textract JobId. */
  def startExpenseAnalysis(sha256: String, storageKey: String): Future[String]

  /** Fetch one page of a GetExpenseAnalysis result. Returns (JobStatus, ExpenseDocuments, Option[NextToken]). */
  def getExpenseAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.ExpensePage]
}

object TextractClient {
  case class AnalysisPage(jobStatus: String, blocks: Seq[play.api.libs.json.JsValue], nextToken: Option[String])
  case class ExpensePage(
      jobStatus: String,
      expenseDocuments: Seq[play.api.libs.json.JsValue],
      nextToken: Option[String]
  )
}
