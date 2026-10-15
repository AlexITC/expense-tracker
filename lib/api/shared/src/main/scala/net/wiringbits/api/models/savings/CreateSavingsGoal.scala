package net.wiringbits.api.models.savings

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object CreateSavingsGoal {
  case class Request(name: String, targetAmount: Option[BigDecimal])
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val requestSchema: Schema[Request] = Schema.derived[Request].name(Schema.SName("CreateSavingsGoalRequest"))
  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateSavingsGoalResponse"))
}
