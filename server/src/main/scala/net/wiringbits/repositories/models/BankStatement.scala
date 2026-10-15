package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate}
import java.util.UUID

case class BankStatement(
    id: UUID,
    documentId: UUID,
    userId: UUID,
    bankName: String,
    productType: String,
    currency: String,
    accountLast4: String,
    periodStart: LocalDate,
    periodEnd: LocalDate,
    createdAt: Instant,
    updatedAt: Instant
)

object BankStatement {
  case class Create(
      id: UUID,
      documentId: UUID,
      userId: UUID,
      createdAt: Instant,
      updatedAt: Instant
  )

  /** Required statement metadata; the draft-import confirm step guarantees every field is present. */
  case class Metadata(
      bankName: String,
      productType: String,
      currency: String,
      accountLast4: String,
      periodStart: LocalDate,
      periodEnd: LocalDate
  )
}
