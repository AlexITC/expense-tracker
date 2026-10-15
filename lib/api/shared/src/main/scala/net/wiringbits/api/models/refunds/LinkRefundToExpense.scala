package net.wiringbits.api.models.refunds

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object LinkRefundToExpense {
  case class Request(expenseId: UUID)
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("LinkRefundToExpenseRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("LinkRefundToExpenseResponse"))
}
