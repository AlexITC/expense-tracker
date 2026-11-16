package net.wiringbits.repositories.models

import net.wiringbits.api.models.subscriptions.SubscriptionExtraChargeRecurrence

import java.time.{Instant, LocalDate}
import java.util.UUID

case class SubscriptionExtraCharge(
    id: UUID,
    userId: UUID,
    subscriptionId: UUID,
    label: String,
    amount: BigDecimal,
    recurrence: SubscriptionExtraChargeRecurrence,
    dueDate: LocalDate,
    createdAt: Instant,
    updatedAt: Instant
)
