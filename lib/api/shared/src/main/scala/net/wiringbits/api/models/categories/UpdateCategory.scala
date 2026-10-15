package net.wiringbits.api.models.categories

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

object UpdateCategory {
  case class Request(name: String)
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("UpdateCategoryRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("UpdateCategoryResponse"))
}
