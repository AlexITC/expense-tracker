package net.wiringbits.repositories.models

import java.time.{Instant, LocalDate}
import java.util.UUID

case class SubscriptionTerm(
    id: UUID,
    userId: UUID,
    subscriptionId: UUID,
    effectiveFrom: LocalDate,
    effectiveTo: Option[LocalDate],
    amountMin: Option[BigDecimal],
    amountMax: Option[BigDecimal],
    createdAt: Instant,
    updatedAt: Instant
)
