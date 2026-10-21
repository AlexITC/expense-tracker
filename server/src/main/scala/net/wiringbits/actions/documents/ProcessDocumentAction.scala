package net.wiringbits.actions.documents

import net.wiringbits.apis.{TextractApiType, TextractClient}
import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, Receipt, TextractJob}
import net.wiringbits.repositories.{
  BackgroundJobsRepository,
  DocumentsRepository,
  ReceiptRepository,
  TextractCacheRepository,
  TextractJobRepository
}
import net.wiringbits.services.{BlobStorage, DocumentClassifier, DocumentTelegramNotifier, PdfTextExtractor}
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class ProcessDocumentAction @Inject() (
    documentsRepository: DocumentsRepository,
    receiptRepository: ReceiptRepository,
    textractCacheRepository: TextractCacheRepository,
    textractJobRepository: TextractJobRepository,
    backgroundJobsRepository: BackgroundJobsRepository,
    textractClient: TextractClient,
    blobStorage: BlobStorage,
    documentClassifier: DocumentClassifier,
    notifier: DocumentTelegramNotifier
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

  private def processReceiptOrInvoice(doc: Document): Future[Unit] =
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
        } else gateThenAnalyze(doc, receipt)
    } yield ()

  /** Classify the document's text layer (cheap) before paying for Textract. If it is confidently not the kind we accept
    * (e.g. a bank voucher dropped into the receipt flow), reject it and skip Textract; otherwise run the analysis.
    */
  private def gateThenAnalyze(doc: Document, receipt: Receipt): Future[Unit] =
    for {
      result <- classify(doc)
      _ <- result.flatMap(_.rejection(DocumentClassifier.acceptedFor(doc.kind))) match {
        case Some(rejected) =>
          logger.info(s"userId=${doc.userId}, document ${doc.id} rejected as $rejected, skipping Textract")
          for {
            _ <- receiptRepository.updateStatus(receipt.id, receipt.userId, DocumentStatus.Unsupported)
            _ <- notifier.notify(doc.id, doc.userId, DocumentTelegramNotifier.notATicketMessage(rejected))
          } yield ()
        case None => analyze(doc, receipt)
      }
    } yield ()

  /** The document's class, classified once from its PDF text layer and then reused. None when there is nothing to
    * classify (missing bytes, no text layer); the caller then proceeds to Textract as without the gate.
    */
  private def classify(doc: Document): Future[Option[DocumentClassifier.Result]] =
    doc.classification match {
      case Some(stored) =>
        logger.info(s"userId=${doc.userId}, document ${doc.id} already classified as ${stored.documentClass}")
        Future.successful(Some(documentClassifier.fromStored(stored)))
      case None =>
        blobStorage
          .get(doc.storageKey)
          .flatMap {
            case None =>
              // FAIL-OPEN, on purpose: Textract surfaces the missing file, the classifier only gates it.
              logger.warn(s"userId=${doc.userId}, document ${doc.id} bytes not found, skipping classification")
              Future.successful(None)
            case Some(bytes) =>
              PdfTextExtractor.extract(bytes) match {
                // An image or a scanned PDF has no text layer to classify cheaply, so it goes to Textract as before.
                case Left(_) => Future.successful(None)
                case Right(text) =>
                  for {
                    // FAIL-OPEN, on purpose: an inconclusive result (JEV error, low confidence) never rejects.
                    result <- documentClassifier.classify(doc.userId, s"document ${doc.id}", text)
                    _ <- result.persistWith(documentsRepository.updateClassification(doc.id, doc.userId, _))
                  } yield Some(result)
              }
          }
          .recover { case NonFatal(e) =>
            // FAIL-OPEN, on purpose: a storage or database error in the optional gate must not block Textract.
            logger.warn(
              s"userId=${doc.userId}, document ${doc.id} classification failed: ${e.getClass.getSimpleName}, skipping it"
            )
            None
          }
    }

  private def analyze(doc: Document, receipt: Receipt): Future[Unit] = {
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
