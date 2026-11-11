package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft as ApiDraft
import net.wiringbits.apis.BedrockFailures
import net.wiringbits.models.documents.DocumentClass
import net.wiringbits.repositories.{BankStatementDraftRepository, DocumentsRepository}
import net.wiringbits.repositories.models.BankStatementDraft as DraftRecord
import net.wiringbits.services.{
  AiStatementExtractor,
  BlobStorage,
  DocumentClassifier,
  PdfTextExtractor,
  StatementExtractionService
}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Background job: read the draft's stored PDF, run the shared extraction ladder, and set the draft's payload
  * (status=ready). Before extracting, the PDF text goes through the AI document classifier: its answer is stored on the
  * draft's document, and a document confidently classified as something other than a bank statement fails the draft
  * without running the (slow, paid) extraction; an inconclusive classification never blocks it. A Bedrock throttle is
  * propagated with the draft left processing, so the background job records the failure and retries it with backoff.
  * Any other hard failure is recorded on the draft (status=failed) rather than propagated, so the job doesn't retry
  * forever and the user sees why; raw AWS SDK messages are replaced with a fixed user-facing one. Skips drafts that are
  * no longer processing, so a duplicate dispatch doesn't re-run the (slow, paid) extraction.
  */
class ExtractBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    documentsRepository: DocumentsRepository,
    blobStorage: BlobStorage,
    extractionService: StatementExtractionService,
    documentClassifier: DocumentClassifier
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(draftId: UUID): Future[Unit] =
    bankStatementDraftRepository.findById(draftId).flatMap {
      case None =>
        logger.warn(s"Draft $draftId not found; skipping extraction")
        Future.unit
      case Some(draft) if draft.status != DraftRecord.Status.Processing =>
        logger.info(s"Draft $draftId is already '${draft.status}'; skipping extraction")
        Future.unit
      case Some(draft) =>
        val run = for {
          bytesOpt <- blobStorage.get(draft.storageKey)
          bytes = bytesOpt.getOrElse(throw new RuntimeException("Statement file not found in storage"))
          text = PdfTextExtractor.extract(bytes).fold(err => throw new RuntimeException(err), identity)
          classification <- documentClassifier.classify(draft.userId, subject = s"draft $draftId", text = text)
          _ <- classification
            .persistWith(c => documentsRepository.updateClassification(id = draft.documentId, userId = draft.userId, c))
          _ <- classification.rejection(Set(DocumentClass.BankStatement)) match {
            case Some(documentClass) => reject(draft, documentClass)
            case None => extract(draft, text)
          }
        } yield ()
        run.recoverWith {
          case ex if BedrockFailures.isThrottled(ex) =>
            logger.warn(
              s"userId=${draft.userId}, draft $draftId extraction throttled (${ex.getMessage}); retrying via job backoff"
            )
            Future.failed(ex)
          case ex if BedrockFailures.isAwsSdkFailure(ex) =>
            logger.warn(s"userId=${draft.userId}, draft $draftId extraction failed with an AWS error", ex)
            bankStatementDraftRepository.markFailed(
              draftId,
              ExtractBankStatementDraftAction.AiServiceFailedMessage,
              clock.instant()
            )
          case ex =>
            logger.warn(s"userId=${draft.userId}, draft $draftId extraction failed: ${ex.getMessage}")
            bankStatementDraftRepository.markFailed(draftId, ex.getMessage, clock.instant())
        }
    }

  private def reject(draft: DraftRecord, documentClass: DocumentClass): Future[Unit] = {
    val reason = documentClass match {
      case DocumentClass.Other => "This doesn't look like a bank statement."
      case _ => s"This looks like ${documentClass.withArticle}, not a bank statement."
    }
    logger.info(s"userId=${draft.userId}, draft ${draft.id}: classified as $documentClass, skipping extraction")
    bankStatementDraftRepository.markFailed(draft.id, reason, clock.instant())
  }

  private def extract(draft: DraftRecord, text: String): Future[Unit] =
    for {
      result <- extractionService.extract(
        label = s"draft ${draft.id}",
        sha256 = draft.sha256,
        storageKey = draft.storageKey,
        text = text
      )
      payload = ExtractBankStatementDraftAction.toPayload(result)
      _ <- bankStatementDraftRepository.markReady(draft.id, Json.toJson(payload).toString, clock.instant())
    } yield ()
}

object ExtractBankStatementDraftAction {

  private[bankstatements] val AiServiceFailedMessage = "The AI extraction service failed; please try uploading again."

  private[bankstatements] def toPayload(result: AiStatementExtractor.ExtractionResult): ApiDraft.Payload =
    ApiDraft.Payload(
      metadata = ApiDraft.Metadata(
        bankName = result.metadata.bankName,
        productType = result.metadata.productType.map(_.entryName),
        currency = result.metadata.currency,
        accountLast4 = result.metadata.accountLast4,
        periodStart = result.metadata.periodStart,
        periodEnd = result.metadata.periodEnd,
        openingBalance = result.metadata.openingBalance,
        closingBalance = result.metadata.closingBalance
      ),
      transactions = result.transactions.map(t =>
        ApiDraft.Transaction(t.date, t.amount, t.description, t.txnType, t.runningBalance)
      ),
      reconciled = result.reconciled,
      discrepancy = result.discrepancy,
      model = result.model,
      stage = result.source,
      unverifiable = result.unverifiable
    )
}
