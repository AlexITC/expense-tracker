package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

case class Expense(
    id: UUID,
    userId: UUID,
    amount: BigDecimal,
    date: LocalDate,
    time: Option[LocalTime],
    merchantId: Option[UUID],
    notes: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

object Expense {
  case class Create(
      id: UUID,
      userId: UUID,
      amount: BigDecimal,
      date: LocalDate,
      time: Option[LocalTime],
      merchantId: Option[UUID],
      notes: Option[String],
      createdAt: Instant,
      updatedAt: Instant
  )
}
