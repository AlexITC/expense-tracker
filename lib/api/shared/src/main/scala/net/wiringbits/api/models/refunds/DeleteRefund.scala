package net.wiringbits.api.models.refunds

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

object DeleteRefund {
  case class Response()

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("DeleteRefundResponse"))
}
