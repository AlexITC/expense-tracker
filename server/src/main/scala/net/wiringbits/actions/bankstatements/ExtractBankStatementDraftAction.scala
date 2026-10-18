package net.wiringbits.actions.bankstatements

import net.wiringbits.api.models.bankstatements.BankStatementDraft as ApiDraft
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.repositories.models.BankStatementDraft as DraftRecord
import net.wiringbits.services.{AiStatementExtractor, BlobStorage, PdfTextExtractor, StatementExtractionService}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Background job: read the draft's stored PDF, run the shared extraction ladder, and set the draft's payload
  * (status=ready). A hard failure is recorded on the draft (status=failed) rather than propagated, so the job doesn't
  * retry forever and the user sees why. Skips drafts that are no longer processing, so a duplicate dispatch doesn't
  * re-run the (slow, paid) extraction.
  */
class ExtractBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    blobStorage: BlobStorage,
    extractionService: StatementExtractionService
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
          result <- extractionService.extract(s"draft $draftId", draft.sha256, draft.storageKey, text)
          payload = ExtractBankStatementDraftAction.toPayload(result)
          _ <- bankStatementDraftRepository.markReady(draftId, Json.toJson(payload).toString, clock.instant())
        } yield ()
        run.recoverWith { case ex =>
          logger.warn(s"Draft $draftId extraction failed: ${ex.getMessage}")
          bankStatementDraftRepository.markFailed(draftId, ex.getMessage, clock.instant())
        }
    }
}

object ExtractBankStatementDraftAction {

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
      stage = result.source
    )
}
