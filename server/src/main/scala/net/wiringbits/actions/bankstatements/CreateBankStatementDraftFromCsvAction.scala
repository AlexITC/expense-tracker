package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.bankstatements.BankStatementDraft as ApiDraft
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.repositories.models.BankStatementDraft as DraftRecord
import net.wiringbits.services.{
  BankStatementDraftReconciliation,
  BankStatementDraftSources,
  BankStatementDraftUploads,
  StatementCsvParser
}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Start a draft import from a CSV: parse it, dedup, store the file, and create the draft directly as `ready` with the
  * parsed rows. There is no extraction job; the user fills the metadata in the review page before confirming.
  */
class CreateBankStatementDraftFromCsvAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    uploads: BankStatementDraftUploads,
    csvParser: StatementCsvParser
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, bytes: Array[Byte], filename: String): Future[ApiDraft.Data] =
    for {
      // Parse before storing anything so an invalid file leaves no blob, document or draft behind.
      transactions <- csvParser.parse(bytes) match {
        case Right(txns) => Future.successful(txns)
        case Left(error) => Future.failed(new RuntimeException(error))
      }
      staged <- uploads.stage(userId, bytes, filename, DocumentKind.BankStatementCsv)
      payload = BankStatementDraftReconciliation.recompute(
        ApiDraft.Payload(
          metadata = ApiDraft.Metadata(
            bankName = None,
            productType = None,
            currency = None,
            accountLast4 = None,
            periodStart = None,
            periodEnd = None,
            openingBalance = None,
            closingBalance = None
          ),
          transactions = transactions,
          reconciled = false,
          discrepancy = None,
          model = "csv",
          stage = None,
          unverifiable = false
        )
      )
      draftId = UuidCreator.getTimeOrderedEpoch()
      draft = DraftRecord.Create(
        id = draftId,
        userId = userId,
        documentId = staged.documentLink.documentId,
        sha256 = staged.sha256,
        filename = filename,
        storageKey = staged.storageKey,
        createdAt = staged.now,
        updatedAt = staged.now
      )
      _ <- bankStatementDraftRepository.createReady(staged.documentLink, draft, Json.toJson(payload).toString)
    } yield {
      logger.info(
        s"Created statement draft $draftId (ready, csv) for userId=$userId, document ${staged.documentLink.documentId} " +
          s"(${staged.linkLabel}), ${transactions.size} rows"
      )
      ApiDraft.Data(
        draftId,
        filename = filename,
        source = BankStatementDraftSources.fromDocumentKind(DocumentKind.BankStatementCsv),
        status = DraftRecord.Status.Ready,
        None,
        Some(payload)
      )
    }
}
