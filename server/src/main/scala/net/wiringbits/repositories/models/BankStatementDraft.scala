package net.wiringbits.repositories.models

import net.wiringbits.models.documents.{DocumentClass, DocumentClassification}

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
    status: String,
    error: Option[String],
    payload: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    aiClassification: Option[DocumentClass],
    aiClassificationConfidence: Option[BigDecimal],
    aiInjectionSuspected: Option[Boolean],
    aiInjectionConfidence: Option[BigDecimal]
) {

  /** The stored AI classification, when the draft was classified (all four columns are set together). */
  def classification: Option[DocumentClassification] =
    DocumentClassification.fromColumns(
      aiClassification,
      aiClassificationConfidence,
      aiInjectionSuspected,
      aiInjectionConfidence
    )
}

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
