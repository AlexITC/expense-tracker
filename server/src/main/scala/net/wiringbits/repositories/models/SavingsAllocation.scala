package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate}
import java.util.UUID

case class SavingsAllocation(
    id: UUID,
    userId: UUID,
    goalId: UUID,
    amount: BigDecimal,
    date: LocalDate,
    note: Option[String],
    merchantId: Option[UUID],
    createdAt: Instant,
    updatedAt: Instant
)

object SavingsAllocation {
  case class Create(
      id: UUID,
      userId: UUID,
      goalId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      note: Option[String],
      merchantId: Option[UUID],
      createdAt: Instant,
      updatedAt: Instant
  )
}
