package net.wiringbits.services

import net.wiringbits.apis.{BdaClient, BedrockClient, TextractApiType, TextractClient}
import net.wiringbits.config.BedrockConfig
import net.wiringbits.repositories.TextractCacheRepository
import net.wiringbits.repositories.models.TextractCache
import net.wiringbits.services.AiStatementExtractor.ExtractionResult
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsValue, Json}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import javax.inject.Inject
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** The shared statement-extraction ladder, used by BOTH the draft-import flow and the reprocess task so they behave
  * identically. Each rung is reconcile-gated and the ladder keeps the best result (one that reconciles wins, else the
  * smallest gap to the balance). A richer representation is tried only when the cheaper one didn't reconcile:
  *   1. PDF text -> LLM 2. BDA standard markdown -> LLM (preserves the charge/credit columns + balance box the flat
  *      text loses) 3. Textract tables + raw text + BDA markdown -> LLM 4. BDA custom (bank-statement blueprint) output
  * -> LLM Any rung's AWS failure is caught and never aborts the extraction — it just keeps the best result so far. BDA
  * and the paid Bedrock call are cached in textract_cache (a generic (hash, type, response) store) so re-runs don't
  * re-call.
  */
class StatementExtractionService @Inject() (
    bedrockClient: BedrockClient,
    bedrockConfig: BedrockConfig,
    textractClient: TextractClient,
    textractCache: TextractCacheRepository,
    bdaClient: BdaClient,
    actorSystem: ActorSystem
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  private val MaxAttempts = 3
  private val PollInterval = 5.seconds
  private val MaxPolls = 60
  private val BdaStandardType = "bda_standard"
  private val BdaCustomType = "bda_custom"
  private val BedrockType = "bedrock_extraction"

  /** Run the full ladder. `label` is only for logging (a draft or statement id). */
  def extract(label: String, sha256: String, storageKey: String, text: String): Future[ExtractionResult] = {
    def rung(prev: ExtractionResult, name: String, input: Future[Option[String]]): Future[ExtractionResult] =
      if (prev.reconciled) Future.successful(prev)
      else
        input
          .flatMap {
            case Some(content) if content.trim.nonEmpty =>
              logger.info(s"[$label] escalating: $name")
              extractReconciling(label, content, attemptNo = 1, prevFailure = None, bestSoFar = Some(prev))
            case _ => Future.successful(prev)
          }
          .recover { case ex =>
            logger.warn(s"[$label] rung '$name' failed (${ex.getMessage}); keeping best so far")
            prev
          }

    for {
      r1 <- extractReconciling(label, text, attemptNo = 1, prevFailure = None, bestSoFar = None)
      r2 <- rung(r1, "BDA markdown", fetchBdaMarkdown(sha256, storageKey))
      r3 <- rung(r2, "Textract + text + BDA markdown", combinedRepresentation(label, sha256, storageKey, text))
      r4 <- rung(r3, "BDA custom output", fetchBdaCustom(sha256, storageKey).map(_.map(Json.prettyPrint)))
    } yield {
      logger.info(s"[$label] extraction done: reconciled=${r4.reconciled} transactions=${r4.transactions.size}")
      r4
    }
  }

  private def combinedRepresentation(
      label: String,
      sha256: String,
      storageKey: String,
      text: String
  ): Future[Option[String]] =
    for {
      tables <- fetchDocumentTables(label, sha256, storageKey)
      md <- fetchBdaMarkdown(sha256, storageKey)
    } yield {
      val sections = List(
        Option.when(tables.trim.nonEmpty)("=== TEXTRACT TABLES ===\n\n" + tables),
        md.filter(_.trim.nonEmpty).map("=== BDA MARKDOWN ===\n\n" + _),
        Some("=== RAW STATEMENT TEXT (for full descriptions / figures not in the above) ===\n\n" + text)
      ).flatten
      Option.when(tables.trim.nonEmpty || md.exists(_.trim.nonEmpty))(sections.mkString("\n\n"))
    }

  private def fetchBdaMarkdown(sha256: String, storageKey: String): Future[Option[String]] =
    textractCache.find(sha256, BdaStandardType).flatMap {
      case Some(cached) => Future.successful((cached.response \ "markdown").asOpt[String])
      case None =>
        bdaClient.standardMarkdown(storageKey).flatMap {
          case Some(md) =>
            textractCache
              .create(TextractCache.Create(sha256, BdaStandardType, Json.obj("markdown" -> md), clock.instant()))
              .map(_ => Some(md))
          case None => Future.successful(None)
        }
    }

  private def fetchBdaCustom(sha256: String, storageKey: String): Future[Option[JsValue]] =
    textractCache.find(sha256, BdaCustomType).flatMap {
      case Some(cached) => Future.successful(Some(cached.response))
      case None =>
        bdaClient.customExtraction(storageKey).flatMap {
          case Some(json) =>
            textractCache
              .create(TextractCache.Create(sha256, BdaCustomType, json, clock.instant()))
              .map(_ => Some(json))
          case None => Future.successful(None)
        }
    }

  private def fetchDocumentTables(label: String, sha256: String, storageKey: String): Future[String] =
    fetchDocumentBlocks(label, sha256, storageKey).map(TextractTableSerializer.serialize)

  private def fetchDocumentBlocks(label: String, sha256: String, storageKey: String): Future[Seq[JsValue]] =
    textractCache.find(sha256, TextractApiType.AnalyzeDocument).flatMap {
      case Some(cached) =>
        Future.successful((cached.response \ "Blocks").asOpt[Seq[JsValue]].getOrElse(Nil))
      case None =>
        textractClient.startDocumentAnalysis(sha256, storageKey).flatMap { jobId =>
          assembleAllDocumentPages(jobId, nextToken = None, acc = Vector.empty, polls = 0).flatMap {
            case (status, blocks) =>
              if ((status == "SUCCEEDED" || status == "PARTIAL_SUCCESS") && blocks.nonEmpty) {
                textractCache
                  .create(
                    TextractCache
                      .Create(sha256, TextractApiType.AnalyzeDocument, Json.obj("Blocks" -> blocks), clock.instant())
                  )
                  .map(_ => blocks)
              } else {
                logger.warn(s"[$label] AnalyzeDocument ended '$status' with ${blocks.size} blocks")
                Future.successful(Seq.empty[JsValue])
              }
          }
        }
    }

  private def assembleAllDocumentPages(
      jobId: String,
      nextToken: Option[String],
      acc: Vector[JsValue],
      polls: Int
  ): Future[(String, Seq[JsValue])] =
    textractClient.getDocumentAnalysis(jobId, nextToken).flatMap { page =>
      page.jobStatus match {
        case status @ ("SUCCEEDED" | "PARTIAL_SUCCESS") =>
          val acc2 = acc ++ page.blocks
          page.nextToken match {
            case Some(token) => assembleAllDocumentPages(jobId, Some(token), acc2, polls)
            case None => Future.successful((status, acc2.toSeq))
          }
        case "IN_PROGRESS" if polls < MaxPolls =>
          after(PollInterval, actorSystem.scheduler)(assembleAllDocumentPages(jobId, nextToken, acc, polls + 1))
        case other => Future.successful((other, acc.toSeq))
      }
    }

  private def extractReconciling(
      label: String,
      text: String,
      attemptNo: Int,
      prevFailure: Option[String],
      bestSoFar: Option[ExtractionResult]
  ): Future[ExtractionResult] =
    cachedExtractBankStatement(text, prevFailure).flatMap { raw =>
      AiStatementExtractor.parseAndVerify(raw, bedrockConfig.statementModelId) match {
        case Right(result) if result.reconciled =>
          Future.successful(result)

        case Right(result) =>
          val best = AiStatementExtractor.bestOf(bestSoFar.toList :+ result).getOrElse(result)
          val failure = result.discrepancy.getOrElse("the transactions did not reconcile")
          if (attemptNo >= MaxAttempts || prevFailure.contains(failure)) {
            logger.info(
              s"[$label] attempt $attemptNo did not reconcile; stopping. Keeping best (off-by=" +
                s"${best.residual.map(_.toString).getOrElse("n/a")})"
            )
            Future.successful(best)
          } else {
            logger.info(s"[$label] attempt $attemptNo did not reconcile ($failure); retrying with feedback")
            extractReconciling(label, text, attemptNo + 1, Some(failure), Some(best))
          }

        case Left(error) =>
          if (attemptNo >= MaxAttempts || prevFailure.contains(error)) {
            bestSoFar match {
              case Some(prior) =>
                logger.warn(s"[$label] attempt $attemptNo produced no usable result ($error); keeping prior best")
                Future.successful(prior)
              case None =>
                logger.warn(s"[$label] attempt $attemptNo produced no usable result; giving up ($error)")
                Future.failed(new RuntimeException(error))
            }
          } else {
            logger.info(s"[$label] attempt $attemptNo produced no usable result ($error); retrying with feedback")
            extractReconciling(label, text, attemptNo + 1, Some(error), bestSoFar)
          }
      }
    }

  private def cachedExtractBankStatement(text: String, feedback: Option[String]): Future[String] = {
    val key = sha256Hex(s"${bedrockConfig.statementModelId}\u0000${feedback.getOrElse("")}\u0000$text")
    textractCache.find(key, BedrockType).flatMap {
      case Some(cached) => Future.successful((cached.response \ "raw").asOpt[String].getOrElse(""))
      case None =>
        bedrockClient.extractBankStatement(text, feedback).flatMap { raw =>
          textractCache
            .create(TextractCache.Create(key, BedrockType, Json.obj("raw" -> raw), clock.instant()))
            .map(_ => raw)
        }
    }
  }

  private def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)).map(b => f"$b%02x").mkString
}
