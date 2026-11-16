package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate

object ChangeSubscriptionPrice {
  // Closes the open term at effectiveFrom (exclusive) and opens a new term with these amounts from that date.
  case class Request(
      effectiveFrom: LocalDate,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  )
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("ChangeSubscriptionPriceRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("ChangeSubscriptionPriceResponse"))
}
