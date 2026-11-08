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
  GetDocumentAnalysisResponse,
  GetExpenseAnalysisRequest,
  GetExpenseAnalysisResponse,
  LineItemFields,
  LineItemGroup,
  S3Object,
  StartDocumentAnalysisRequest,
  StartDocumentAnalysisResponse,
  StartExpenseAnalysisRequest,
  StartExpenseAnalysisResponse
}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

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
