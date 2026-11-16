package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.{SubscriptionDescription, SubscriptionName}
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.util.UUID

object UpdateSubscription {
  // name and description are validated primitives enforced at decode by their Format. Amounts and dates live in the
  // subscription's terms, which this request does not change.
  case class Request(
      name: SubscriptionName,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: Option[UUID],
      merchantId: Option[UUID],
      description: Option[SubscriptionDescription]
  )
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("UpdateSubscriptionRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("UpdateSubscriptionResponse"))
}
