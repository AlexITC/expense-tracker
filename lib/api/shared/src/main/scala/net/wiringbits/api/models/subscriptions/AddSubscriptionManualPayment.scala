package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

object AddSubscriptionManualPayment {
  // amount is validated by the action rather than at decode.
  case class Request(
      paidDate: LocalDate,
      amount: BigDecimal,
      note: Option[SubscriptionManualPaymentNote]
  )
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("AddSubscriptionManualPaymentRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("AddSubscriptionManualPaymentResponse"))
}
