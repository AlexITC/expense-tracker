package net.wiringbits.api.models.subscriptions

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.util.UUID

object GetSubscriptionReconciliation {
  case class Response(data: List[Response.Subscription])

  object Response {
    case class Subscription(
        subscriptionId: UUID,
        // Ordered by dueDate.
        cycles: List[SubscriptionCycle],
        // Ordered by paidDate, then id. Every manual payment is listed, including those not credited to any cycle.
        manualPayments: List[SubscriptionManualPayment]
    )
  }

  // Declared here rather than in `object Response`: the derived Schema's helpers are compiled onto this outer
  // object, so a nested declaration creates a static-init cycle when `Response` is touched first.
  implicit val subscriptionFormat: Format[Response.Subscription] = Json.format[Response.Subscription]
  implicit val subscriptionSchema: Schema[Response.Subscription] =
    Schema.derived[Response.Subscription].name(Schema.SName("GetSubscriptionReconciliationSubscription"))

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetSubscriptionReconciliationResponse"))
}
