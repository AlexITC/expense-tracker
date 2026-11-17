package net.wiringbits.repositories.models

import net.wiringbits.models.documents.DocumentKind

import java.time.Instant
import java.util.UUID

/** A per-user draft import. Extraction runs in a background job, so a draft starts as `processing` with no payload and
  * becomes `ready` (payload set) or `failed` (error set). `payload` is the extraction JSON (the shared
  * BankStatementDraft.Payload, serialized).
  */
case class BankStatementDraft(
    id: UUID,
    userId: UUID,
    documentId: UUID,
    sha256: String,
    filename: String,
    storageKey: String,
    // kind of the linked document (read via join), which tells a PDF draft from a CSV one
    documentKind: DocumentKind,
    status: String,
    error: Option[String],
    payload: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

object BankStatementDraft {
  object Status {
    val Processing = "processing"
    val Ready = "ready"
    val Failed = "failed"
  }

  /** The document behind a new draft: an existing one (a re-upload after discard) or a new one to insert with it. */
  enum DocumentLink {
    case Existing(id: UUID)
    case New(document: Document.Create)

    def documentId: UUID = this match {
      case Existing(id) => id
      case New(document) => document.id
    }
  }

  case class Create(
      id: UUID,
      userId: UUID,
      documentId: UUID,
      sha256: String,
      filename: String,
      storageKey: String,
      createdAt: Instant,
      updatedAt: Instant
  )
}
