package net.wiringbits.api.models.merchants

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

object DeleteImportRule {
  case class Response()

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("DeleteImportRuleResponse"))
}
