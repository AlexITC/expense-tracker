package net.wiringbits.repositories.models

import net.wiringbits.common.models.SubscriptionManualPaymentNote

import java.time.{Instant, LocalDate}
import java.util.UUID

case class SubscriptionManualPayment(
    id: UUID,
    userId: UUID,
    subscriptionId: UUID,
    paidDate: LocalDate,
    amount: BigDecimal,
    note: Option[SubscriptionManualPaymentNote],
    createdAt: Instant,
    updatedAt: Instant
)
