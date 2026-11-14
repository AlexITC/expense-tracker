package net.wiringbits.api.models.categories

import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.{CategoryDescription, CategoryName}
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

object UpdateCategory {
  // See CreateCategory: name and description are validated primitives, enforced at decode by their Format.
  case class Request(name: CategoryName, description: Option[CategoryDescription])
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("UpdateCategoryRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("UpdateCategoryResponse"))
}
