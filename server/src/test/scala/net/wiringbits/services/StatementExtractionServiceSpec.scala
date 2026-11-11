package net.wiringbits.services

import net.wiringbits.api.models.bankstatementtemplates.ExtractionSource
import net.wiringbits.apis.{BdaClient, BedrockClient, BedrockRateLimitedException}
import net.wiringbits.config.BedrockConfig
import net.wiringbits.core.RepositorySpec
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures.*
import org.scalatest.matchers.must.Matchers.*
import play.api.libs.json.JsValue
import software.amazon.awssdk.regions.Region
import utils.FailingTextractClient

import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.{Success, Try}

class StatementExtractionServiceSpec extends RepositorySpec with BeforeAndAfterAll {

  private val system: ActorSystem = ActorSystem("StatementExtractionServiceSpec")
  private implicit val clock: Clock = Clock.systemUTC()

  override def afterAll(): Unit = {
    system.terminate()
    system.whenTerminated.futureValue
    super.afterAll()
  }

  private val bedrockConfig = BedrockConfig(
    accessKeyId = AWSAccessKeyId("test"),
    secretAccessKey = AWSSecretAccessKey("test"),
    region = Region.US_EAST_1,
    modelId = "test-model",
    statementModelId = "test-statement-model",
    statementMaxTokens = 1000,
    suggestionsModelId = "test-suggestions-model",
    requestsPerMinute = 10,
    mode = BedrockConfig.Mock
  )

  private class RecordingBedrock(respond: (String, Option[String]) => String) extends BedrockClient {
    val calls = new ConcurrentLinkedQueue[(String, Option[String])]()
    override def extractBankStatement(statementText: String, feedback: Option[String]): Future[String] = {
      calls.add((statementText, feedback))
      Future.successful(respond(statementText, feedback))
    }
    override def suggest(prompt: String): Future[String] = Future.failed(new RuntimeException("unexpected suggest"))
  }

  private class RecordingBda(markdown: Option[String]) extends BdaClient {
    val calls = new ConcurrentLinkedQueue[String]()
    override def standardMarkdown(storageKey: String): Future[Option[String]] = {
      calls.add(s"standardMarkdown:$storageKey")
      Future.successful(markdown)
    }
    override def customExtraction(storageKey: String): Future[Option[JsValue]] = {
      calls.add(s"customExtraction:$storageKey")
      Future.successful(None)
    }
  }

  private class RecordingTextract extends FailingTextractClient {
    val calls = new ConcurrentLinkedQueue[String]()
    override def startDocumentAnalysis(sha256: String, storageKey: String): Future[String] = {
      calls.add(storageKey)
      super.startDocumentAnalysis(sha256, storageKey)
    }
  }

  private def statement(opening: Option[String], closing: Option[String], rows: List[(String, Option[String])]) = {
    val balances = opening.map(o => s""","openingBalance":$o""").getOrElse("") +
      closing.map(c => s""","closingBalance":$c""").getOrElse("")
    val txns = rows
      .map { case (amount, running) =>
        val rb = running.map(r => s""","runningBalance":$r""").getOrElse("")
        s"""{"date":"2026-01-15","amount":$amount,"description":"TEST PURCHASE"$rb}"""
      }
      .mkString(",")
    s"""{"metadata":{"bankName":"Test Bank","productType":"debit","currency":"EUR","accountLast4":"0000",""" +
      s""""periodStart":"2026-01-01","periodEnd":"2026-01-31"$balances},"transactions":[$txns]}"""
  }

  private val noBalances = statement(None, None, List("-40.00" -> None))
  private val reconciling = statement(Some("100.00"), Some("60.00"), List("-40.00" -> Some("60.00")))
  private val chainBreak = statement(Some("100.00"), None, List("-40.00" -> Some("50.00")))
  private val netMismatch = statement(Some("100.00"), Some("50.00"), List("-40.00" -> None))
  private val noTransactions = statement(Some("100.00"), Some("60.00"), Nil)

  private final case class Run(
      outcome: Try[AiStatementExtractor.ExtractionResult],
      bedrockCalls: List[(String, Option[String])],
      bdaCalls: List[String],
      textractCalls: List[String]
  ) {
    def result: AiStatementExtractor.ExtractionResult = outcome.get
  }

  /** Runs the ladder with a unique text/sha256 so the shared cache never serves another test's responses. The PdfText
    * rung gets `text` (containing the marker); the BdaMarkdown rung gets the BDA markdown, which never contains "PDF".
    */
  private def runLadder(pdfText: Option[String] => String, markdownResponse: String): Run =
    withRepositories() { repositories =>
      val marker = UUID.randomUUID().toString
      val text = s"PDF statement $marker"
      val markdown = s"BDA markdown $marker"
      val bedrock = new RecordingBedrock((input, feedback) =>
        if (input == text) pdfText(feedback)
        else if (input == markdown) markdownResponse
        else statement(None, None, Nil)
      )
      val bda = new RecordingBda(Some(markdown))
      val textract = new RecordingTextract
      val service =
        new StatementExtractionService(bedrock, bedrockConfig, textract, repositories.textractCache, bda, system)
      val outcome = service
        .extract(label = "test", sha256 = marker, storageKey = s"key-$marker", text = text)
        .transform(Success(_))
        .futureValue
      Run(outcome, bedrock.calls.asScala.toList, bda.calls.asScala.toList, textract.calls.asScala.toList)
    }

  "extract" should {
    "stop at the first rung when it yields transactions but no balances to verify them" in {
      val run = runLadder(_ => noBalances, markdownResponse = reconciling)

      run.bedrockCalls.size mustBe 1
      run.bedrockCalls.head._2 mustBe None
      run.bdaCalls mustBe empty
      run.textractCalls mustBe empty
      run.result.unverifiable mustBe true
      run.result.reconciled mustBe false
      run.result.transactions.size mustBe 1
      run.result.source mustBe Some(ExtractionSource.PdfText)
    }

    "retry rather than stop when the first rung extracts no transactions" in {
      val run = runLadder(_ => noTransactions, markdownResponse = reconciling)

      run.bedrockCalls.filter(_._1.startsWith("PDF")).map(_._2) mustBe List(
        None,
        Some("The model extracted no transactions")
      )
      // With no candidate at all from the first rung the extraction fails (unchanged Left path); it is never
      // mistaken for a terminal unverifiable result.
      run.outcome.failed.get.getMessage mustBe "The model extracted no transactions"
      run.bdaCalls mustBe empty
    }

    "escalate to BDA markdown when a retry after zero transactions still doesn't reconcile" in {
      val run = runLadder(
        {
          case None => noTransactions
          case Some(_) => netMismatch
        },
        markdownResponse = reconciling
      )

      run.bedrockCalls.filter(_._1.startsWith("PDF")).map(_._2).take(2) mustBe List(
        None,
        Some("The model extracted no transactions")
      )
      run.bdaCalls.exists(_.startsWith("standardMarkdown")) mustBe true
      run.result.reconciled mustBe true
      run.result.source mustBe Some(ExtractionSource.BdaMarkdown)
    }

    "retry with feedback and escalate on a running-balance chain break" in {
      val run = runLadder(_ => chainBreak, markdownResponse = reconciling)

      val pdfCalls = run.bedrockCalls.filter(_._1.startsWith("PDF"))
      pdfCalls.size mustBe 2
      pdfCalls(1)._2.exists(_.contains("running balance does not add up")) mustBe true
      run.bdaCalls.exists(_.startsWith("standardMarkdown")) mustBe true
      run.result.reconciled mustBe true
      run.result.source mustBe Some(ExtractionSource.BdaMarkdown)
    }

    "retry with feedback and escalate on an opening/closing balance mismatch" in {
      val run = runLadder(_ => netMismatch, markdownResponse = reconciling)

      val pdfCalls = run.bedrockCalls.filter(_._1.startsWith("PDF"))
      pdfCalls.size mustBe 2
      pdfCalls(1)._2.exists(_.contains("off by")) mustBe true
      run.bdaCalls.exists(_.startsWith("standardMarkdown")) mustBe true
      run.result.reconciled mustBe true
      run.result.source mustBe Some(ExtractionSource.BdaMarkdown)
    }

    "keep a chain break over a retry that drops the balances" in {
      val run = runLadder(
        {
          case None => chainBreak
          case Some(_) => noBalances
        },
        markdownResponse = noBalances
      )

      run.bdaCalls.exists(_.startsWith("standardMarkdown")) mustBe true
      run.result.reconciled mustBe false
      run.result.unverifiable mustBe false
      run.result.discrepancy.exists(_.contains("running balance does not add up")) mustBe true
      run.result.source mustBe Some(ExtractionSource.PdfText)
    }

    "keep the PDF stage's balance mismatch over an unverifiable BDA markdown result" in {
      val run = runLadder(_ => netMismatch, markdownResponse = noBalances)

      run.bdaCalls.exists(_.startsWith("standardMarkdown")) mustBe true
      run.result.reconciled mustBe false
      run.result.unverifiable mustBe false
      run.result.discrepancy.exists(_.contains("off by")) mustBe true
      run.result.source mustBe Some(ExtractionSource.PdfText)
    }

    "fail with the throttle when a later rung's Bedrock call is rate limited" in withRepositories() { repositories =>
      val marker = UUID.randomUUID().toString
      val text = s"PDF statement $marker"
      val bedrock = new BedrockClient {
        override def extractBankStatement(statementText: String, feedback: Option[String]): Future[String] =
          if (statementText == text) Future.successful(netMismatch)
          else Future.failed(new BedrockRateLimitedException("limit reached"))
        override def suggest(prompt: String): Future[String] =
          Future.failed(new RuntimeException("unexpected suggest"))
      }
      val service = new StatementExtractionService(
        bedrock,
        bedrockConfig,
        new RecordingTextract,
        repositories.textractCache,
        new RecordingBda(Some(s"BDA markdown $marker")),
        system
      )

      val error = service
        .extract(label = "test", sha256 = marker, storageKey = s"key-$marker", text = text)
        .failed
        .futureValue

      error mustBe a[BedrockRateLimitedException]
    }
  }
}
