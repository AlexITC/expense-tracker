package net.wiringbits.api.models.savings

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.LocalDate
import java.util.UUID

object CreateSavingsAllocation {
  case class Request(amount: BigDecimal, date: LocalDate, note: Option[String], merchantId: Option[UUID])
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateSavingsAllocationRequest"))
  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateSavingsAllocationResponse"))
}
