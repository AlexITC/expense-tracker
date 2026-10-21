package net.wiringbits.actions.documents

import net.wiringbits.apis.{TextractApiType, TextractClient}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Receipt, TextractJob}
import net.wiringbits.repositories.{
  BackgroundJobsRepository,
  DocumentsRepository,
  ReceiptRepository,
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
    receiptRepository: ReceiptRepository,
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
      _ <- doc.kind match {
        // Bank statements are imported via the AI draft flow and have no receipt row, so there is nothing to process.
        case DocumentKind.BankStatementPdf =>
          logger.info(
            s"Skipping Textract for bank statement ${doc.id} (userId=${doc.userId}); statements are imported via the AI draft flow"
          )
          Future.unit
        case _ => processReceiptOrInvoice(doc)
      }
    } yield ()

  private def processReceiptOrInvoice(doc: net.wiringbits.repositories.models.Document): Future[Unit] =
    for {
      receiptOpt <- receiptRepository.findByDocumentId(doc.id)
      receipt = receiptOpt.getOrElse(throw new RuntimeException(s"Receipt not found for document ${doc.id}"))
      _ <-
        if (
          receipt.status == DocumentStatus.Parsed || receipt.status == DocumentStatus.ParseFailed || receipt.status == DocumentStatus.Unsupported
        ) {
          logger.info(
            s"Receipt ${receipt.id} (userId=${receipt.userId}) already in terminal status ${receipt.status}, skipping"
          )
          Future.unit
        } else analyze(doc, receipt)
    } yield ()

  private def analyze(doc: net.wiringbits.repositories.models.Document, receipt: Receipt): Future[Unit] = {
    logger.info(s"Processing document ${doc.id} (userId=${doc.userId}, sha256=${doc.sha256}, kind=${doc.kind})")
    for {
      _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Processing)
      cachedOpt <- textractCacheRepository.find(doc.sha256, TextractApiType.AnalyzeExpense)
      _ <- cachedOpt match {
        case Some(_) =>
          logger.info(
            s"Textract cache hit for receipt ${receipt.id} (userId=${receipt.userId}), enqueuing parse directly"
          )
          for {
            _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Analyzed)
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
  }

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
