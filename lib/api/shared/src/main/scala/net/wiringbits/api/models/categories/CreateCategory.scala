package net.wiringbits.api.models.categories

import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.{CategoryDescription, CategoryName}
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.util.UUID

object CreateCategory {
  // name and description are validated primitives: their length limits and messages live in CategoryName /
  // CategoryDescription and are enforced at decode by their Format, so this model carries no separate caps.
  case class Request(name: CategoryName, description: Option[CategoryDescription])
  case class Response(id: UUID, name: String)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("CreateCategoryRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateCategoryResponse"))
}
