package net.wiringbits.actions.bankstatements

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.api.models.bankstatements.BankStatementDraft as ApiDraft
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.models.jobs.{BackgroundJobPayload, BackgroundJobStatus, BackgroundJobType}
import net.wiringbits.repositories.models.{BackgroundJobData, Document, BankStatementDraft as DraftRecord}
import net.wiringbits.repositories.{BankStatementDraftRepository, BankStatementRepository, DocumentsRepository}
import net.wiringbits.services.BlobStorage
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.time.{Clock, Instant, LocalDate}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** Start a draft import: dedup, store the PDF, create the draft as `processing`, and enqueue a background job that
  * extracts it with AI. Returns immediately; the UI polls the draft until it becomes ready/failed.
  */
class CreateBankStatementDraftAction @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    documentsRepository: DocumentsRepository,
    bankStatementRepository: BankStatementRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext, clock: Clock) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def apply(userId: UUID, bytes: Array[Byte], filename: String): Future[ApiDraft.Data] = {
    val sha256 = computeSha256(bytes)
    for {
      existingDrafts <- bankStatementDraftRepository.listByUser(userId)
      _ = if (existingDrafts.exists(_.sha256 == sha256))
        throw new RuntimeException("This file is already in your import queue")
      existingDoc <- documentsRepository.findBySha256(userId, sha256)
      _ <- existingDoc match {
        case Some(doc) =>
          bankStatementRepository.findByDocumentIdInternal(doc.id).map {
            case Some(_) => throw new RuntimeException("This statement was already imported")
            case None => ()
          }
        case None => Future.unit
      }
      storageKey = s"data/$userId/${LocalDate.now(clock)}/$sha256"
      alreadyUploaded <- blobStorage.exists(storageKey)
      _ <- if (alreadyUploaded) Future.unit else blobStorage.put(storageKey, bytes)
      now = clock.instant()
      documentLink = resolveDocument(existingDoc, userId, sha256, storageKey, filename, now)
      draftId = UuidCreator.getTimeOrderedEpoch()
      draft = DraftRecord.Create(draftId, userId, documentLink.documentId, sha256, filename, storageKey, now, now)
      job = BackgroundJobData.Create(
        id = UUID.randomUUID(),
        `type` = BackgroundJobType.ExtractBankStatementDraft,
        payload = BackgroundJobPayload.ExtractBankStatementDraft(draftId),
        status = BackgroundJobStatus.Pending,
        executeAt = now,
        createdAt = now,
        updatedAt = now
      )
      _ <- bankStatementDraftRepository.create(documentLink, draft, job)
    } yield {
      val linkLabel = documentLink match {
        case DraftRecord.DocumentLink.New(_) => "new"
        case DraftRecord.DocumentLink.Existing(_) => "reused"
      }
      logger.info(
        s"Created statement draft $draftId (processing) for user $userId, document ${documentLink.documentId} " +
          s"($linkLabel); enqueued extraction job"
      )
      ApiDraft.Data(draftId, filename, DraftRecord.Status.Processing, None, None)
    }
  }

  /** The document outlives a discarded draft, so a re-upload links to it instead of inserting a duplicate (the (user,
    * sha256) pair is unique). A file already uploaded as another kind of document can't become a statement.
    */
  private def resolveDocument(
      existing: Option[Document],
      userId: UUID,
      sha256: String,
      storageKey: String,
      filename: String,
      now: Instant
  ): DraftRecord.DocumentLink = existing match {
    case Some(doc) if doc.kind == DocumentKind.BankStatementPdf => DraftRecord.DocumentLink.Existing(doc.id)
    case Some(doc) => throw AlreadyUploadedAsOtherKind(doc.kind)
    case None =>
      DraftRecord.DocumentLink.New(
        Document.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          sha256 = sha256,
          storageKey = storageKey,
          kind = DocumentKind.BankStatementPdf,
          filename = filename,
          createdAt = now,
          updatedAt = now
        )
      )
  }

  private def computeSha256(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString
  }
}

private final case class AlreadyUploadedAsOtherKind(kind: DocumentKind)
    extends RuntimeException(s"This file was already uploaded as a ${kind.entryName} document")
