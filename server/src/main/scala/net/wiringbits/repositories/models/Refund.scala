package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate}
import java.util.UUID

case class Refund(
    id: UUID,
    userId: UUID,
    amount: BigDecimal,
    date: LocalDate,
    merchantId: Option[UUID],
    categoryId: Option[UUID],
    expenseId: Option[UUID],
    notes: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

object Refund {
  case class Create(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      expenseId: Option[UUID],
      notes: Option[String],
      createdAt: Instant,
      updatedAt: Instant
  )
}
