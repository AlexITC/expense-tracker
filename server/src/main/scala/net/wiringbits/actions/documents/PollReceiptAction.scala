package net.wiringbits.actions.documents

import net.wiringbits.apis.{TextractApiType, TextractClient}
import net.wiringbits.models.documents.DocumentStatus
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, TextractCache, TextractJob}
import net.wiringbits.repositories.{
  BackgroundJobsRepository,
  DocumentsRepository,
  TextractCacheRepository,
  TextractJobRepository
}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class PollReceiptAction @Inject() (
    documentsRepository: DocumentsRepository,
    textractCacheRepository: TextractCacheRepository,
    textractJobRepository: TextractJobRepository,
    backgroundJobsRepository: BackgroundJobsRepository,
    textractClient: TextractClient
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  private val InitialPollDelaySeconds = 5L
  private val MaxPollDelaySeconds = 30L
  private val MaxAttempts = 15

  def apply(documentId: UUID): Future[Unit] =
    for {
      docOpt <- documentsRepository.findById(documentId)
      doc = docOpt.getOrElse(throw new RuntimeException(s"Document not found: $documentId"))
      _ <-
        if (isTerminal(doc.status)) {
          logger.info(s"Document $documentId already terminal (${doc.status}), skipping poll")
          Future.unit
        } else poll(doc)
    } yield ()

  private def isTerminal(s: DocumentStatus): Boolean =
    s == DocumentStatus.Parsed || s == DocumentStatus.Unsupported || s == DocumentStatus.ParseFailed

  private def poll(doc: net.wiringbits.repositories.models.Document): Future[Unit] =
    textractCacheRepository.find(doc.sha256, TextractApiType.AnalyzeExpense).flatMap {
      case Some(_) =>
        logger.info(s"Cache hit for document ${doc.id}, going directly to parse")
        for {
          _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Analyzed, None)
          _ <- enqueueParseReceipt(doc.id)
        } yield ()
      case None =>
        pollTextractJob(doc)
    }

  private def pollTextractJob(doc: net.wiringbits.repositories.models.Document): Future[Unit] =
    textractJobRepository.findByDocumentId(doc.id).flatMap {
      case None =>
        logger.error(s"No TextractJob found for document ${doc.id}, marking Unsupported")
        documentsRepository.updateStatus(doc.id, DocumentStatus.Unsupported, None)
      case Some(job) =>
        job.status match {
          case TextractJob.Status.Succeeded =>
            logger.info(s"TextractJob already succeeded for document ${doc.id}, enqueuing parse")
            for {
              _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Analyzed, None)
              _ <- enqueueParseReceipt(doc.id)
            } yield ()
          case TextractJob.Status.Failed =>
            logger.warn(s"TextractJob already failed for document ${doc.id}, marking Unsupported")
            documentsRepository.updateStatus(doc.id, DocumentStatus.Unsupported, None)
          case _ =>
            val jobId = job.jobId.getOrElse(throw new RuntimeException(s"TextractJob ${job.id} has no jobId"))
            fetchAndHandleResult(doc, job, jobId)
        }
    }

  private def fetchAndHandleResult(
      doc: net.wiringbits.repositories.models.Document,
      job: TextractJob,
      jobId: String
  ): Future[Unit] =
    assembleAllExpensePages(jobId).flatMap { case (finalStatus, allExpenseDocs) =>
      finalStatus match {
        case s if s == "SUCCEEDED" || s == "PARTIAL_SUCCESS" =>
          val assembled = Json.obj(
            "DocumentMetadata" -> Json.obj("Pages" -> 1),
            "ExpenseDocuments" -> JsArray(allExpenseDocs)
          )
          for {
            _ <- textractCacheRepository.create(
              TextractCache.Create(doc.sha256, TextractApiType.AnalyzeExpense, assembled, clock.instant())
            )
            _ <- textractJobRepository.markSucceeded(job.id)
            _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Analyzed, None)
            _ <- enqueueParseReceipt(doc.id)
          } yield ()
        case "FAILED" =>
          logger.warn(s"Textract expense job $jobId FAILED for document ${doc.id}")
          for {
            _ <- textractJobRepository.markFailed(job.id)
            _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Unsupported, None)
          } yield ()
        case _ => // IN_PROGRESS
          if (job.attempts >= MaxAttempts) {
            logger.warn(s"Document ${doc.id} exceeded max poll attempts (${job.attempts}), marking Unsupported")
            for {
              _ <- textractJobRepository.markFailed(job.id)
              _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Unsupported, None)
            } yield ()
          } else {
            val delay = math.min(InitialPollDelaySeconds * (job.attempts + 1), MaxPollDelaySeconds)
            logger.info(s"Document ${doc.id} IN_PROGRESS (attempt=${job.attempts}), re-enqueueing in ${delay}s")
            for {
              _ <- textractJobRepository.incrementAttempts(job.id)
              _ <- enqueueNextPoll(doc.id, delay)
            } yield ()
          }
      }
    }

  private def assembleAllExpensePages(jobId: String): Future[(String, Seq[JsValue])] = {
    def loop(nextToken: Option[String], acc: Seq[JsValue]): Future[(String, Seq[JsValue])] =
      textractClient.getExpenseAnalysis(jobId, nextToken).flatMap { page =>
        page.jobStatus match {
          case "IN_PROGRESS" | "FAILED" => Future.successful((page.jobStatus, Seq.empty))
          case status =>
            val combined = acc ++ page.expenseDocuments
            page.nextToken match {
              case Some(token) => loop(Some(token), combined)
              case None => Future.successful((status, combined))
            }
        }
      }
    loop(None, Seq.empty)
  }

  private def enqueueParseReceipt(documentId: UUID): Future[Unit] = {
    val now = clock.instant()
    backgroundJobsRepository.create(
      BackgroundJobData.Create(
        id = UUID.randomUUID(),
        `type` = BackgroundJobType.ParseReceipt,
        payload = BackgroundJobPayload.ParseReceipt(documentId),
        status = BackgroundJobStatus.Pending,
        executeAt = now,
        createdAt = now,
        updatedAt = now
      )
    )
  }

  private def enqueueNextPoll(documentId: UUID, delaySeconds: Long): Future[Unit] = {
    val now = clock.instant()
    backgroundJobsRepository.create(
      BackgroundJobData.Create(
        id = UUID.randomUUID(),
        `type` = BackgroundJobType.PollReceipt,
        payload = BackgroundJobPayload.PollReceipt(documentId),
        status = BackgroundJobStatus.Pending,
        executeAt = now.plusSeconds(delaySeconds),
        createdAt = now,
        updatedAt = now
      )
    )
  }
}
