package net.wiringbits.api.models.bankstatements

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

object SetAccountAlias {
  val MaxAliasLength = 60

  case class Request(alias: String)
  case class Response(alias: String)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema
      .derived[Request]
      .name(Schema.SName("SetAccountAliasRequest"))
      .modify(_.alias)(_.description(s"At most $MaxAliasLength characters"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("SetAccountAliasResponse"))
}
