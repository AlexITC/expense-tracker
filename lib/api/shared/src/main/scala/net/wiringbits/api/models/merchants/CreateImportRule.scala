package net.wiringbits.api.models.merchants

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object CreateImportRule {
  case class Request(action: ImportRuleAction, pattern: String, merchantId: Option[UUID])
  case class Response(id: UUID)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateImportRuleRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateImportRuleResponse"))
}
