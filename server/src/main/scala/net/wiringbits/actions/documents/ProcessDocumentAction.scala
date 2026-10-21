package net.wiringbits.actions.documents

import net.wiringbits.apis.{TextractApiType, TextractClient}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, TextractJob}
import net.wiringbits.repositories.{
  BackgroundJobsRepository,
  DocumentsRepository,
  TextractCacheRepository,
  TextractJobRepository
}
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ProcessDocumentAction @Inject() (
    documentsRepository: DocumentsRepository,
    textractCacheRepository: TextractCacheRepository,
    textractJobRepository: TextractJobRepository,
    backgroundJobsRepository: BackgroundJobsRepository,
    textractClient: TextractClient
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(documentId: UUID): Future[Unit] =
    for {
      docOpt <- documentsRepository.findById(documentId)
      doc = docOpt.getOrElse(throw new RuntimeException(s"Document not found: $documentId"))
      _ <-
        if (
          doc.status == DocumentStatus.Parsed || doc.status == DocumentStatus.ParseFailed || doc.status == DocumentStatus.Unsupported
        ) {
          logger.info(s"Document $documentId already in terminal status ${doc.status}, skipping")
          Future.unit
        } else process(doc)
    } yield ()

  private def process(doc: net.wiringbits.repositories.models.Document): Future[Unit] = {
    logger.info(s"Processing document ${doc.id} (sha256=${doc.sha256}, kind=${doc.kind})")
    doc.kind match {
      // Bank statement documents are created as Ingested at upload and imported via the AI draft flow; they must never go to Textract.
      case DocumentKind.BankStatementPdf =>
        logger.info(s"Skipping Textract for bank statement ${doc.id}; statements are imported via the AI draft flow")
        Future.unit
      case _ => processReceiptOrInvoice(doc)
    }
  }

  private def processReceiptOrInvoice(doc: net.wiringbits.repositories.models.Document): Future[Unit] =
    for {
      _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Processing, None)
      cachedOpt <- textractCacheRepository.find(doc.sha256, TextractApiType.AnalyzeExpense)
      _ <- cachedOpt match {
        case Some(_) =>
          logger.info(s"Textract cache hit for receipt ${doc.id}, enqueuing parse directly")
          for {
            _ <- documentsRepository.updateStatus(doc.id, DocumentStatus.Analyzed, None)
            _ <- enqueueParseReceipt(doc.id)
          } yield ()
        case None =>
          for {
            jobId <- textractClient.startExpenseAnalysis(doc.sha256, doc.storageKey)
            now = clock.instant()
            _ <- textractJobRepository.create(
              TextractJob.Create(
                id = com.github.f4b6a3.uuid.UuidCreator.getTimeOrderedEpoch(),
                userId = doc.userId,
                documentId = doc.id,
                apiType = TextractApiType.AnalyzeExpense,
                jobId = jobId,
                status = TextractJob.Status.InProgress,
                createdAt = now,
                updatedAt = now
              )
            )
            _ <- enqueuePollReceipt(doc.id)
          } yield ()
      }
    } yield ()

  private def enqueuePollReceipt(documentId: UUID): Future[Unit] = {
    val now = clock.instant()
    backgroundJobsRepository.create(
      BackgroundJobData.Create(
        id = UUID.randomUUID(),
        `type` = BackgroundJobType.PollReceipt,
        payload = BackgroundJobPayload.PollReceipt(documentId),
        status = BackgroundJobStatus.Pending,
        executeAt = now.plusSeconds(5),
        createdAt = now,
        updatedAt = now
      )
    )
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
}
