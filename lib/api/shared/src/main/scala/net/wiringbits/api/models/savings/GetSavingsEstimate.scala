package net.wiringbits.api.models.savings

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

object GetSavingsEstimate {
  case class Response(typicalAmount: Option[BigDecimal])

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetSavingsEstimateResponse"))
}
