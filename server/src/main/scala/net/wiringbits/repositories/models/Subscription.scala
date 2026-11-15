package net.wiringbits.repositories.models

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}

import java.time.{Instant, LocalDate}
import java.util.UUID

case class Subscription(
    id: UUID,
    userId: UUID,
    name: String,
    cadence: SubscriptionCadence,
    intervalDays: Option[Int],
    amountMin: Option[BigDecimal],
    amountMax: Option[BigDecimal],
    paymentMethod: SubscriptionPaymentMethod,
    categoryId: Option[UUID],
    startDate: LocalDate,
    createdAt: Instant,
    updatedAt: Instant
)

object Subscription {
  case class Create(
      id: UUID,
      userId: UUID,
      name: String,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      startDate: LocalDate,
      createdAt: Instant,
      updatedAt: Instant
  )
}
