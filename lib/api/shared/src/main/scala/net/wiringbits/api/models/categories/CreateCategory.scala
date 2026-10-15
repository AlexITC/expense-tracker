package net.wiringbits.api.models.categories

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.util.UUID

object CreateCategory {
  case class Request(name: String)
  case class Response(id: UUID, name: String)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateCategoryRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateCategoryResponse"))
}
