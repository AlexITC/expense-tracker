package net.wiringbits.apis

import play.api.libs.json.{JsValue, Json}

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** Mock Textract client for tests. No AWS calls are made.
  *
  * Async document-analysis lifecycle (configurable):
  *   - startDocumentAnalysis returns a fake JobId.
  *   - getDocumentAnalysis returns IN_PROGRESS for the first `inProgressPolls` calls, then SUCCEEDED with a paginated
  *     canned AnalyzeDocument response. The canned response is split across `pageCount` pages, each containing a
  *     distinct subset of the blocks.
  *
  * Async expense-analysis lifecycle (same parameters):
  *   - startExpenseAnalysis returns a fake JobId.
  *   - getExpenseAnalysis returns IN_PROGRESS for the first `inProgressPolls` calls, then SUCCEEDED with a canned
  *     AnalyzeExpense-shaped response (one ExpenseDocument with empty SummaryFields/LineItemGroups).
  *
  * To simulate failure scenarios, set `failJobId` to the JobId that should return FAILED.
  */
class MockTextractClient(
    inProgressPolls: Int = 2,
    pageCount: Int = 2
) extends TextractClient {

  private case class JobState(pollCount: Int = 0, failed: Boolean = false)

  private val docJobs = new ConcurrentHashMap[String, JobState]()
  private val expenseJobs = new ConcurrentHashMap[String, JobState]()

  def analyzeExpense(sha256: String, storageKey: String): Future[JsValue] =
    Future.successful(Json.obj("JobStatus" -> "SUCCEEDED", "ExpenseDocuments" -> Json.arr()))

  override def startDocumentAnalysis(sha256: String, storageKey: String): Future[String] = {
    val jobId = UUID.randomUUID().toString
    docJobs.put(jobId, JobState())
    Future.successful(jobId)
  }

  /** Force the given jobId to fail on the next getDocumentAnalysis call. */
  def failNextDocPoll(jobId: String): Unit =
    Option(docJobs.get(jobId)).foreach(s => docJobs.put(jobId, s.copy(failed = true)))

  override def getDocumentAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.AnalysisPage] = {
    val state = Option(docJobs.get(jobId)).getOrElse(JobState())

    if (state.failed) {
      Future.successful(TextractClient.AnalysisPage("FAILED", Seq.empty, None))
    } else if (state.pollCount < inProgressPolls) {
      docJobs.put(jobId, state.copy(pollCount = state.pollCount + 1))
      Future.successful(TextractClient.AnalysisPage("IN_PROGRESS", Seq.empty, None))
    } else {
      val pageIndex = nextToken.flatMap(t => t.stripPrefix("page-").toIntOption).getOrElse(0)
      val allBlocks = MockTextractClient.cannedBlocks
      val chunkSize = math.max(1, math.ceil(allBlocks.size.toDouble / pageCount).toInt)
      val pageBlocks = allBlocks.slice(pageIndex * chunkSize, (pageIndex + 1) * chunkSize)
      val next = if ((pageIndex + 1) * chunkSize < allBlocks.size) Some(s"page-${pageIndex + 1}") else None
      Future.successful(TextractClient.AnalysisPage("SUCCEEDED", pageBlocks, next))
    }
  }

  override def startExpenseAnalysis(sha256: String, storageKey: String): Future[String] = {
    val jobId = UUID.randomUUID().toString
    expenseJobs.put(jobId, JobState())
    Future.successful(jobId)
  }

  /** Force the given jobId to fail on the next getExpenseAnalysis call. */
  def failNextExpensePoll(jobId: String): Unit =
    Option(expenseJobs.get(jobId)).foreach(s => expenseJobs.put(jobId, s.copy(failed = true)))

  override def getExpenseAnalysis(
      jobId: String,
      nextToken: Option[String]
  ): Future[TextractClient.ExpensePage] = {
    val state = Option(expenseJobs.get(jobId)).getOrElse(JobState())

    if (state.failed) {
      Future.successful(TextractClient.ExpensePage("FAILED", Seq.empty, None))
    } else if (state.pollCount < inProgressPolls) {
      expenseJobs.put(jobId, state.copy(pollCount = state.pollCount + 1))
      Future.successful(TextractClient.ExpensePage("IN_PROGRESS", Seq.empty, None))
    } else {
      Future.successful(TextractClient.ExpensePage("SUCCEEDED", MockTextractClient.cannedExpenseDocs, None))
    }
  }
}

object MockTextractClient {
  // Minimal AnalyzeDocument blocks: one TABLE with a CELL and two WORDs — enough to exercise
  // table extraction and pagination without pulling in real fixture data.
  val cannedBlocks: Seq[JsValue] = {
    val wordId1 = "word-1"
    val wordId2 = "word-2"
    val cellId = "cell-1"
    val tableId = "table-1"
    val pageId = "page-1"
    Seq(
      Json.obj("BlockType" -> "PAGE", "Id" -> pageId, "Page" -> 1),
      Json.obj(
        "BlockType" -> "TABLE",
        "Id" -> tableId,
        "Page" -> 1,
        "Relationships" -> Json.arr(Json.obj("Type" -> "CHILD", "Ids" -> Json.arr(cellId)))
      ),
      Json.obj(
        "BlockType" -> "CELL",
        "Id" -> cellId,
        "RowIndex" -> 1,
        "ColumnIndex" -> 1,
        "Page" -> 1,
        "Relationships" -> Json.arr(Json.obj("Type" -> "CHILD", "Ids" -> Json.arr(wordId1, wordId2)))
      ),
      Json.obj("BlockType" -> "WORD", "Id" -> wordId1, "Text" -> "Hello", "Page" -> 1),
      Json.obj("BlockType" -> "WORD", "Id" -> wordId2, "Text" -> "World", "Page" -> 1)
    )
  }

  /** Build a minimal assembled AnalyzeDocument response from these canned blocks. */
  val cannedAssembledResponse: JsValue =
    Json.obj(
      "DocumentMetadata" -> Json.obj("Pages" -> 1),
      "JobStatus" -> "SUCCEEDED",
      "Blocks" -> Json.toJson(cannedBlocks),
      "AnalyzeDocumentModelVersion" -> "1.0"
    )

  // Minimal AnalyzeExpense-shaped expense documents: one doc with empty fields — enough
  // to exercise the async lifecycle and have DocumentParser return a valid (empty) ParsedData.
  val cannedExpenseDocs: Seq[JsValue] = Seq(
    Json.obj("SummaryFields" -> Json.arr(), "LineItemGroups" -> Json.arr())
  )

  /** Build a minimal assembled AnalyzeExpense response from the canned expense docs. */
  val cannedExpenseResponse: JsValue =
    Json.obj(
      "DocumentMetadata" -> Json.obj("Pages" -> 1),
      "ExpenseDocuments" -> Json.toJson(cannedExpenseDocs)
    )
}
