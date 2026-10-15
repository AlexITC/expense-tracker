package net.wiringbits.api.models.categories

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetCategories {
  case class Response(data: List[Response.Category])

  object Response {
    case class Category(id: UUID, name: String, createdAt: Instant)
    implicit val categoryFormat: Format[Category] = Json.format[Category]
    implicit val categorySchema: Schema[Category] =
      Schema.derived[Category].name(Schema.SName("GetCategoriesResponseCategory"))
  }

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] = Schema
    .derived[Response]
    .name(Schema.SName("GetCategoriesResponse"))
    .description("The authenticated user's categories")
}
