package net.wiringbits.apis

import net.wiringbits.config.AWSConfig
import play.api.libs.json.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.textract.TextractAsyncClient
import software.amazon.awssdk.services.textract.model.{
  AnalyzeExpenseRequest,
  AnalyzeExpenseResponse,
  Document,
  DocumentLocation,
  ExpenseDetection,
  ExpenseDocument,
  ExpenseField,
  FeatureType,
  GetDocumentAnalysisRequest,
  GetExpenseAnalysisRequest,
  LineItemFields,
  LineItemGroup,
  S3Object,
  StartDocumentAnalysisRequest,
  StartExpenseAnalysisRequest
}

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

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
  case class AnalysisPage(jobStatus: String, blocks: Seq[JsValue], nextToken: Option[String])
  case class ExpensePage(
      jobStatus: String,
      expenseDocuments: Seq[JsValue],
      nextToken: Option[String]
  )
}

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

class RealTextractClient(awsConfig: AWSConfig)(implicit ec: ExecutionContext) extends TextractClient {

  private val client: TextractAsyncClient = {
    val credentials =
      AwsBasicCredentials.create(awsConfig.accessKeyId.string, awsConfig.secretAccessKey.string)
    TextractAsyncClient
      .builder()
      .region(awsConfig.region)
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .build()
  }

  override def analyzeExpense(sha256: String, storageKey: String): Future[JsValue] = {
    val s3Object = S3Object.builder().bucket(awsConfig.s3.bucket).name(storageKey).build()
    val document = Document.builder().s3Object(s3Object).build()
    val request = AnalyzeExpenseRequest.builder().document(document).build()
    client.analyzeExpense(request).asScala.map(serializeExpenseResponse)
  }

  override def startDocumentAnalysis(sha256: String, storageKey: String): Future[String] = {
    val s3Object = S3Object.builder().bucket(awsConfig.s3.bucket).name(storageKey).build()
    val docLocation = DocumentLocation.builder().s3Object(s3Object).build()
    val request = StartDocumentAnalysisRequest
      .builder()
      .documentLocation(docLocation)
      .featureTypes(FeatureType.TABLES)
      .build()
    client.startDocumentAnalysis(request).asScala.map(_.jobId())
  }

  override def getDocumentAnalysis(
      jobId: String,
      nextToken: Option[String]
  ): Future[TextractClient.AnalysisPage] = {
    val builder = GetDocumentAnalysisRequest.builder().jobId(jobId)
    nextToken.foreach(builder.nextToken)
    client.getDocumentAnalysis(builder.build()).asScala.map { resp =>
      val blocks = resp.blocks().asScala.toSeq.map(serializeBlock)
      TextractClient.AnalysisPage(resp.jobStatusAsString(), blocks, Option(resp.nextToken()).filter(_.nonEmpty))
    }
  }

  override def startExpenseAnalysis(sha256: String, storageKey: String): Future[String] = {
    val s3Object = S3Object.builder().bucket(awsConfig.s3.bucket).name(storageKey).build()
    val docLocation = DocumentLocation.builder().s3Object(s3Object).build()
    val request = StartExpenseAnalysisRequest.builder().documentLocation(docLocation).build()
    client.startExpenseAnalysis(request).asScala.map(_.jobId())
  }

  override def getExpenseAnalysis(jobId: String, nextToken: Option[String]): Future[TextractClient.ExpensePage] = {
    val builder = GetExpenseAnalysisRequest.builder().jobId(jobId)
    nextToken.foreach(builder.nextToken)
    client.getExpenseAnalysis(builder.build()).asScala.map { resp =>
      val docs = resp.expenseDocuments().asScala.toSeq.map(serializeExpenseDoc)
      TextractClient.ExpensePage(resp.jobStatusAsString(), docs, Option(resp.nextToken()).filter(_.nonEmpty))
    }
  }

  private def serializeBlock(block: software.amazon.awssdk.services.textract.model.Block): JsValue = {
    val base = Json.obj(
      "BlockType" -> block.blockTypeAsString(),
      "Id" -> block.id(),
      "Page" -> Option(block.page()).map(_.toInt)
    )
    val withText = if (block.text() != null) base ++ Json.obj("Text" -> block.text()) else base
    val withIndices =
      if (block.rowIndex() != null)
        withText ++ Json.obj("RowIndex" -> block.rowIndex().toInt, "ColumnIndex" -> block.columnIndex().toInt)
      else withText
    val rels = block.relationships().asScala.toSeq
    if (rels.nonEmpty)
      withIndices ++ Json.obj(
        "Relationships" -> JsArray(
          rels.map(r => Json.obj("Type" -> r.typeAsString(), "Ids" -> JsArray(r.ids().asScala.map(JsString(_)).toSeq)))
        )
      )
    else withIndices
  }

  private def serializeDetection(d: ExpenseDetection): JsObject = Json.obj(
    "Text" -> Option(d.text()),
    "Confidence" -> Option(d.confidence()).map(c => BigDecimal(c.doubleValue()))
  )

  private def serializeExpenseField(f: ExpenseField): JsObject = Json.obj(
    "Type" -> Option(f.`type`()).map(t => Json.obj("Text" -> t.text())),
    "ValueDetection" -> Option(f.valueDetection()).map(serializeDetection),
    "LabelDetection" -> Option(f.labelDetection()).map(serializeDetection),
    "PageNumber" -> f.pageNumber().toInt
  )

  private def serializeLineItem(li: LineItemFields): JsObject = Json.obj(
    "LineItemExpenseFields" -> JsArray(li.lineItemExpenseFields().asScala.map(serializeExpenseField).toSeq)
  )

  private def serializeLineItemGroup(g: LineItemGroup): JsObject = Json.obj(
    "LineItemGroupIndex" -> g.lineItemGroupIndex().toInt,
    "LineItems" -> JsArray(g.lineItems().asScala.map(serializeLineItem).toSeq)
  )

  private def serializeExpenseDoc(doc: ExpenseDocument): JsObject = Json.obj(
    "SummaryFields" -> JsArray(doc.summaryFields().asScala.map(serializeExpenseField).toSeq),
    "LineItemGroups" -> JsArray(doc.lineItemGroups().asScala.map(serializeLineItemGroup).toSeq)
  )

  private def serializeExpenseResponse(response: AnalyzeExpenseResponse): JsValue = {
    Json.obj(
      "DocumentMetadata" -> Json.obj("Pages" -> response.documentMetadata().pages().toInt),
      "ExpenseDocuments" -> JsArray(response.expenseDocuments().asScala.map(serializeExpenseDoc).toSeq)
    )
  }
}
