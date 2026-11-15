package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

object AddSubscriptionExtraCharge {
  // label is a validated primitive enforced at decode; amount follows SubscriptionValidator.validateExtraChargeAmount.
  case class Request(
      label: SubscriptionExtraChargeLabel,
      amount: BigDecimal,
      recurrence: SubscriptionExtraChargeRecurrence,
      dueDate: LocalDate
  )
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("AddSubscriptionExtraChargeRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("AddSubscriptionExtraChargeResponse"))
}
