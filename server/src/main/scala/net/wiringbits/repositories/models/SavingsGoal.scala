package net.wiringbits.repositories.models

import java.time.Instant
import java.util.UUID

case class SavingsGoal(
    id: UUID,
    userId: UUID,
    name: String,
    targetAmount: Option[BigDecimal],
    createdAt: Instant,
    updatedAt: Instant
)

object SavingsGoal {
  case class Create(
      id: UUID,
      userId: UUID,
      name: String,
      targetAmount: Option[BigDecimal],
      createdAt: Instant,
      updatedAt: Instant
  )
}
