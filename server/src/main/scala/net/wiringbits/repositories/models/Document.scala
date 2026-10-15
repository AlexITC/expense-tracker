package net.wiringbits.repositories.models

import net.wiringbits.models.documents.{DocumentKind, DocumentStatus}

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
    updatedAt: Instant
)

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
