package net.wiringbits.services

import com.github.f4b6a3.uuid.UuidCreator
import net.wiringbits.models.documents.DocumentKind
import net.wiringbits.repositories.models.{Document, BankStatementDraft as DraftRecord}
import net.wiringbits.repositories.{BankStatementDraftRepository, BankStatementRepository, DocumentsRepository}

import java.security.MessageDigest
import java.time.{Clock, Instant, LocalDate}
import java.util.HexFormat
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/** The upload steps every draft source shares: dedup against the queue and confirmed statements, store the file, and
  * resolve the document the draft links to.
  */
class BankStatementDraftUploads @Inject() (
    bankStatementDraftRepository: BankStatementDraftRepository,
    documentsRepository: DocumentsRepository,
    bankStatementRepository: BankStatementRepository,
    blobStorage: BlobStorage
)(implicit ec: ExecutionContext, clock: Clock) {

  def stage(userId: UUID, bytes: Array[Byte], filename: String, kind: DocumentKind): Future[StagedUpload] = {
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
      now = clock.instant()
      // Resolved before storing so a file rejected as another kind leaves no blob behind.
      documentLink = resolveDocument(
        existingDoc,
        kind,
        userId,
        sha256 = sha256,
        storageKey = storageKey,
        filename = filename,
        now
      )
      alreadyUploaded <- blobStorage.exists(storageKey)
      _ <- if (alreadyUploaded) Future.unit else blobStorage.put(storageKey, bytes)
    } yield StagedUpload(sha256 = sha256, storageKey = storageKey, documentLink = documentLink, now = now)
  }

  /** The document outlives a discarded draft, so a re-upload links to it instead of inserting a duplicate (the (user,
    * sha256) pair is unique). A file already uploaded as another kind of document can't become this kind of statement.
    */
  private def resolveDocument(
      existing: Option[Document],
      expectedKind: DocumentKind,
      userId: UUID,
      sha256: String,
      storageKey: String,
      filename: String,
      now: Instant
  ): DraftRecord.DocumentLink = existing match {
    case Some(doc) if doc.kind == expectedKind => DraftRecord.DocumentLink.Existing(doc.id)
    case Some(doc) => throw AlreadyUploadedAsOtherKind(doc.kind)
    case None =>
      DraftRecord.DocumentLink.New(
        Document.Create(
          id = UuidCreator.getTimeOrderedEpoch(),
          userId = userId,
          sha256 = sha256,
          storageKey = storageKey,
          kind = expectedKind,
          filename = filename,
          createdAt = now,
          updatedAt = now
        )
      )
  }

  private def computeSha256(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** A deduplicated, stored upload ready to become a draft. */
case class StagedUpload(sha256: String, storageKey: String, documentLink: DraftRecord.DocumentLink, now: Instant) {
  def linkLabel: String = documentLink match {
    case DraftRecord.DocumentLink.New(_) => "new"
    case DraftRecord.DocumentLink.Existing(_) => "reused"
  }
}

private final case class AlreadyUploadedAsOtherKind(kind: DocumentKind)
    extends RuntimeException(s"This file was already uploaded as a ${kind.entryName} document")
