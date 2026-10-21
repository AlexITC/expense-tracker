package net.wiringbits.repositories.models

import net.wiringbits.models.documents.DocumentStatus

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

case class Receipt(
    id: UUID,
    userId: UUID,
    documentId: UUID,
    amount: Option[BigDecimal],
    date: Option[LocalDate],
    time: Option[LocalTime],
    merchantName: Option[String],
    taxId: Option[String],
    merchantId: Option[UUID],
    expenseId: Option[UUID],
    refundId: Option[UUID],
    ignoredAt: Option[Instant],
    manuallyEdited: Boolean,
    parserVersion: Option[String],
    status: DocumentStatus,
    createdAt: Instant,
    updatedAt: Instant,
    documentFilename: String,
    documentStatus: DocumentStatus
)

object Receipt {
  case class Create(
      id: UUID,
      userId: UUID,
      documentId: UUID,
      manuallyEdited: Boolean,
      status: DocumentStatus,
      createdAt: Instant,
      updatedAt: Instant
  )
}
