package net.wiringbits.repositories.models

import net.wiringbits.models.documents.{DocumentClass, DocumentClassification, DocumentKind, DocumentStatus}

import java.time.Instant
import java.util.UUID

case class Document(
    id: UUID,
    userId: UUID,
    sha256: String,
    storageKey: String,
    kind: DocumentKind,
    filename: String,
    status: DocumentStatus,
    parserVersion: Option[String],
    createdAt: Instant,
    updatedAt: Instant,
    aiClassification: Option[DocumentClass],
    aiClassificationConfidence: Option[BigDecimal],
    aiInjectionSuspected: Option[Boolean],
    aiInjectionConfidence: Option[BigDecimal]
) {

  /** The stored AI classification, when the document was classified (all four columns are set together). */
  def classification: Option[DocumentClassification] =
    for {
      documentClass <- aiClassification
      confidence <- aiClassificationConfidence
      injectionSuspected <- aiInjectionSuspected
      injectionConfidence <- aiInjectionConfidence
    } yield DocumentClassification(documentClass, confidence, injectionSuspected, injectionConfidence)
}

object Document {
  case class Create(
      id: UUID,
      userId: UUID,
      sha256: String,
      storageKey: String,
      kind: DocumentKind,
      filename: String,
      status: DocumentStatus,
      createdAt: Instant,
      updatedAt: Instant
  )
}
