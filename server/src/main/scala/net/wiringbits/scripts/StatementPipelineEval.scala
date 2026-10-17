package net.wiringbits.scripts

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import net.wiringbits.apis.{RealBdaClient, RealBedrockClient, RealTextractClient}
import net.wiringbits.config.{AWSConfig, BdaConfig, BedrockConfig, S3Config}
import net.wiringbits.executors.DatabaseExecutionContext
import net.wiringbits.models.{AWSAccessKeyId, AWSSecretAccessKey}
import net.wiringbits.repositories.TextractCacheRepository
import net.wiringbits.services.AiStatementExtractor.ExtractionResult
import net.wiringbits.services.{PdfTextExtractor, S3BlobStorage, StatementExtractionService}
import org.apache.pekko.actor.ActorSystem
import play.api.db.Databases
import play.api.libs.json.{JsObject, Json}
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region

import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.time.Clock
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** Runs the REAL statement-extraction ladder (StatementExtractionService: PDF text -> BDA markdown -> Textract+text+BDA
  * -> BDA custom, reconcile-gated, keep-best) against the synthetic PDFs under EVAL_DIR/statements, and writes the
  * parse for each one as `results/<lang>_<name>.parsed.json` in the SAME shape as the fixture JSON (metadata +
  * transactions, money as 2-decimal strings) so it diffs directly against its fixture. Each parse also carries two keys
  * beyond the fixture shape: `producedBy` (which ladder stage produced it, from the service's INFO logs) and the
  * `reconciled` flag. Scoring is a separate concern: see `score.py`, which reads these parses + the fixtures.
  *
  * Needs real AWS (Bedrock + Textract + BDA), the S3 bucket, and a Postgres with the textract_cache table. On a re-run
  * everything is served from that cache, so EVAL_SKIP_UPLOAD=1 lets it avoid S3 entirely. Driven by env vars:
  * {{{
  *   EVAL_DIR=/path/to/eval            # holds statements/<lang>/<name>.pdf + <name>.json; parses land in results/
  *   EVAL_DB_URL=jdbc:postgresql://... # a Postgres with the textract_cache table
  *   AWS_REGION  AWS_S3_BUCKET
  *   BDA_PROFILE_ARN  BDA_STANDARD_PROJECT_ARN  BDA_BANK_STATEMENT_BLUEPRINT_ARN  BDA_OUTPUT_S3_URI
  *   BEDROCK_STATEMENT_MODEL_ID        # optional; defaults to eu.anthropic.claude-sonnet-4-5
  *   EVAL_SKIP_UPLOAD=1                # optional; skip S3 upload on a cache-served re-run
  *   sbt "server/runMain net.wiringbits.scripts.StatementPipelineEval"
  * }}}
  * Score the parses it writes with `tools/score.py`.
  */
object StatementPipelineEval {

  def main(args: Array[String]): Unit = {
    val evalDir = Paths.get(sys.env("EVAL_DIR"))
    val statementsDir = evalDir.resolve("statements")
    val resultsDir = evalDir.resolve("results")
    Files.createDirectories(resultsDir)

    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val clock: Clock = Clock.systemUTC()
    implicit val dbEc: DatabaseExecutionContext = new DatabaseExecutionContext {
      def execute(r: Runnable): Unit = ec.execute(r)
      def reportFailure(t: Throwable): Unit = t.printStackTrace()
    }
    val system = ActorSystem("statement-eval")

    val creds = DefaultCredentialsProvider.builder().build().resolveCredentials()
    val region = Region.of(sys.env.getOrElse("AWS_REGION", "eu-central-1"))
    val bucket = sys.env("AWS_S3_BUCKET")
    val statementModelId =
      sys.env.getOrElse("BEDROCK_STATEMENT_MODEL_ID", "eu.anthropic.claude-sonnet-4-5-20250929-v1:0")
    val key = AWSAccessKeyId(creds.accessKeyId())
    val secret = AWSSecretAccessKey(creds.secretAccessKey())

    val awsConfig = AWSConfig(key, secret, region, S3Config(bucket, None))
    val bedrockConfig =
      BedrockConfig(key, secret, region, statementModelId, statementModelId, 16000, BedrockConfig.Real)
    val bdaConfig = BdaConfig(
      BdaConfig.Real,
      sys.env("BDA_PROFILE_ARN"),
      sys.env("BDA_STANDARD_PROJECT_ARN"),
      sys.env("BDA_BANK_STATEMENT_BLUEPRINT_ARN"),
      sys.env("BDA_OUTPUT_S3_URI")
    )

    val bedrock = new RealBedrockClient(bedrockConfig)
    val textract = new RealTextractClient(awsConfig)
    val bda = new RealBdaClient(awsConfig, bdaConfig, system)
    val blob = new S3BlobStorage(awsConfig)
    val database = Databases("org.postgresql.Driver", sys.env("EVAL_DB_URL"), name = "statement-eval")
    val cache = new TextractCacheRepository(database)
    val service = new StatementExtractionService(bedrock, bedrockConfig, textract, cache, bda, system)

    val svcLogger = org.slf4j.LoggerFactory.getLogger(classOf[StatementExtractionService]).asInstanceOf[LogbackLogger]
    svcLogger.setLevel(Level.INFO)
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    svcLogger.addAppender(appender)

    val pdfs = Files
      .walk(statementsDir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".pdf"))
      .toList
      .sortBy(_.toString)

    pdfs.foreach { pdf =>
      appender.list.clear()
      val label = statementsDir.relativize(pdf).toString // e.g. en/charge_card.pdf

      Try {
        val bytes = Files.readAllBytes(pdf)
        val sha = sha256Hex(bytes)
        // On a re-run the ladder is served from textract_cache and never reads S3; EVAL_SKIP_UPLOAD avoids S3 then.
        if (!sys.env.contains("EVAL_SKIP_UPLOAD")) Await.result(blob.put(s"eval-seed567/$label", bytes), 2.minutes)
        val text = PdfTextExtractor.extract(bytes).fold(e => throw new RuntimeException(s"PDF text: $e"), identity)
        val result = Await.result(service.extract(label, sha, s"eval-seed567/$label", text), 25.minutes)

        val escalations = appender.list.asScala.toList
          .map(_.getFormattedMessage)
          .filter(_.contains("escalating:"))
          .map(m => m.substring(m.indexOf("escalating:") + "escalating:".length).trim)
        val attempted = "PDF text" :: escalations
        val winner = if (result.reconciled) attempted.last else "none - kept best"

        // Fixture shape plus provenance: which ladder stage produced this, and the full path it tried.
        val parsed = fixtureShaped(result) ++ Json.obj("producedBy" -> winner, "attemptedRungs" -> attempted)
        Files.write(
          resultsDir.resolve(label.replace('/', '_').stripSuffix(".pdf") + ".parsed.json"),
          Json.prettyPrint(parsed).getBytes
        )
        println(s"[eval] $label -> producedBy=$winner reconciled=${result.reconciled} rows=${result.transactions.size}")
      } match {
        case Success(_) => ()
        case Failure(e) => println(s"[eval] $label -> ERROR: ${e.getMessage}")
      }
    }

    println(s"\nWrote parsed JSON (fixture shape + producedBy/reconciled) to $resultsDir; score with score.py")

    database.shutdown()
    Await.result(system.terminate(), 30.seconds)
    sys.exit(0)
  }

  private def fixtureShaped(r: ExtractionResult): JsObject = {
    def money(b: BigDecimal): String = b.setScale(2, BigDecimal.RoundingMode.HALF_UP).toString
    Json.obj(
      "metadata" -> Json.obj(
        "bankName" -> r.metadata.bankName,
        "productType" -> r.metadata.productType.map(_.entryName),
        "currency" -> r.metadata.currency,
        "accountLast4" -> r.metadata.accountLast4,
        "periodStart" -> r.metadata.periodStart.map(_.toString),
        "periodEnd" -> r.metadata.periodEnd.map(_.toString),
        "openingBalance" -> r.metadata.openingBalance.map(money),
        "closingBalance" -> r.metadata.closingBalance.map(money)
      ),
      "transactions" -> r.transactions.map(t =>
        Json.obj(
          "date" -> t.date.toString,
          "amount" -> money(t.amount),
          "description" -> t.description,
          "runningBalance" -> t.runningBalance.map(money),
          "type" -> t.txnType
        )
      ),
      "reconciled" -> r.reconciled
    )
  }

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"$b%02x").mkString
}
