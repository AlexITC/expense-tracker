package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate}
import java.util.UUID

case class BankStatementRow(
    id: UUID,
    bankStatementId: UUID,
    index: Int,
    date: LocalDate,
    amount: BigDecimal,
    description: String,
    manuallyEdited: Boolean,
    confirmedAt: Option[Instant],
    expenseId: Option[UUID],
    refundId: Option[UUID],
    ignoredAt: Option[Instant],
    txnType: String,
    createdAt: Instant,
    updatedAt: Instant
)

object BankStatementRow {
  case class Create(
      id: UUID,
      bankStatementId: UUID,
      index: Int,
      date: LocalDate,
      amount: BigDecimal,
      description: String,
      manuallyEdited: Boolean,
      createdAt: Instant,
      updatedAt: Instant,
      txnType: String
  )
}
