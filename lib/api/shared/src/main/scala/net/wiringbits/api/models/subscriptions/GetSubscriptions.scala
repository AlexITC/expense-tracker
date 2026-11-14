package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.{Instant, LocalDate}
import java.util.UUID

object GetSubscriptions {
  case class Response(data: List[Response.Subscription])

  object Response {
    case class Subscription(
        id: UUID,
        name: String,
        cadence: SubscriptionCadence,
        intervalDays: Option[Int],
        amountMin: Option[BigDecimal],
        amountMax: Option[BigDecimal],
        paymentMethod: SubscriptionPaymentMethod,
        categoryId: Option[UUID],
        startDate: LocalDate,
        createdAt: Instant
    )

    implicit val subscriptionFormat: Format[Subscription] = Json.format[Subscription]
    implicit val subscriptionSchema: Schema[Subscription] =
      Schema.derived[Subscription].name(Schema.SName("GetSubscriptionsSubscription"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetSubscriptionsResponse"))
}
