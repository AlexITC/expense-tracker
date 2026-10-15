package net.wiringbits.api.models.refunds

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.LocalDate
import java.util.UUID

object CreateRefund {
  case class Request(
      amount: BigDecimal,
      date: LocalDate,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      expenseId: Option[UUID],
      notes: Option[String]
  )
  case class Response(id: UUID, amount: BigDecimal, date: LocalDate)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] = Schema.derived[Request].name(Schema.SName("CreateRefundRequest"))
  implicit val responseSchema: Schema[Response] = Schema.derived[Response].name(Schema.SName("CreateRefundResponse"))
}
