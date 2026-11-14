package net.wiringbits.api.models.merchants

import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.MerchantName
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object CreateMerchant {
  // name is a validated primitive: its length limit and messages live in MerchantName and are enforced at decode by
  // its Format, so this model carries no separate cap.
  case class Request(name: MerchantName, notes: Option[String], categoryId: Option[UUID], taxId: Option[String] = None)
  case class Response(id: UUID, name: String)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateMerchantRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateMerchantResponse"))
}
