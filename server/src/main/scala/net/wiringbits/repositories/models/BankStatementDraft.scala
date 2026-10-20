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
    for {
      documentClass <- aiClassification
      confidence <- aiClassificationConfidence
      injectionSuspected <- aiInjectionSuspected
      injectionConfidence <- aiInjectionConfidence
    } yield DocumentClassification(documentClass, confidence, injectionSuspected, injectionConfidence)
}

object BankStatementDraft {
  object Status {
    val Processing = "processing"
    val Ready = "ready"
    val Failed = "failed"
  }

  case class Create(
      id: UUID,
      userId: UUID,
      sha256: String,
      filename: String,
      storageKey: String,
      createdAt: Instant,
      updatedAt: Instant
  )
}
