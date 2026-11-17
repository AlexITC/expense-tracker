package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.bankstatements.BankStatementDraft as ApiDraft
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.BankStatementDraftRepository
import net.wiringbits.repositories.models.{BackgroundJobData, BankStatementDraft as DraftRecord}
import net.wiringbits.services.{BankStatementDraftSources, BankStatementDraftUploads}
import org.slf4j.LoggerFactory

import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Start a draft import: dedup, store the PDF, create the draft as `processing`, and enqueue a background job that
  * extracts it with AI. Returns immediately; the UI polls the draft until it becomes ready/failed.
  */
class CreateBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    uploads: BankStatementDraftUploads
)(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, bytes: Array[Byte], filename: String): Future[ApiDraft.Data] =
    for {
      staged <- uploads.stage(userId, bytes, filename, DocumentKind.BankStatementPdf)
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
      job = BackgroundJobData.Create(
        id = UUID.randomUUID(),
        `type` = BackgroundJobType.ExtractBankStatementDraft,
        payload = BackgroundJobPayload.ExtractBankStatementDraft(draftId),
        status = BackgroundJobStatus.Pending,
        executeAt = staged.now,
        createdAt = staged.now,
        updatedAt = staged.now
      )
      _ <- bankStatementDraftRepository.create(staged.documentLink, draft, job)
    } yield {
      logger.info(
        s"Created statement draft $draftId (processing) for user $userId, document ${staged.documentLink.documentId} " +
          s"(${staged.linkLabel}); enqueued extraction job"
      )
      ApiDraft.Data(
        draftId,
        filename = filename,
        source = BankStatementDraftSources.fromDocumentKind(DocumentKind.BankStatementPdf),
        status = DraftRecord.Status.Processing,
        None,
        None
      )
    }
}
