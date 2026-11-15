package net.wiringbits.repositories.models

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod}

import java.time.Instant
import java.util.UUID

case class Subscription(
    id: UUID,
    userId: UUID,
    name: String,
    cadence: SubscriptionCadence,
    intervalDays: Option[Int],
    intervalMonths: Option[Int],
    paymentMethod: SubscriptionPaymentMethod,
    categoryId: Option[UUID],
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
      intervalMonths: Option[Int],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      firstTerm: SubscriptionTerm,
      createdAt: Instant,
      updatedAt: Instant
  )

  // terms are ordered by effectiveFrom.
  case class WithTerms(subscription: Subscription, terms: List[SubscriptionTerm])
}
