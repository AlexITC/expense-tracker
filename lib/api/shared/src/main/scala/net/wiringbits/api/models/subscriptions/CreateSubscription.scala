package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.SubscriptionName
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

object CreateSubscription {
  // name is a validated primitive: its length limit and messages live in SubscriptionName and are enforced at
  // decode by its Format, so this model carries no separate caps.
  case class Request(
      name: SubscriptionName,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      startDate: LocalDate
  )
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateSubscriptionRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateSubscriptionResponse"))
}
