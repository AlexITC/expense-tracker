package net.wiringbits.api.models.categories

import play.api.libs.json.{Format, Json}
import sttp.tapir.{optionModifyFunctor, ModifyEach, Schema, Validator}

import java.util.UUID

object CreateCategory {
  val MaxNameTransportLength: Int = 100
  val MaxDescriptionTransportLength: Int = 2000

  case class Request(name: String, description: Option[String] = None)
  case class Response(id: UUID, name: String)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema
      .derived[Request]
      .name(Schema.SName("CreateCategoryRequest"))
      // Transport caps that only bound the payload size; the exact limits and their messages are enforced by the
      // server's ValidateCategoryFields, so inputs between those limits and these caps still get those messages.
      .modify(_.name)(_.validate(Validator.maxLength[String](MaxNameTransportLength)))
      .modify(_.description.each)(_.validate(Validator.maxLength[String](MaxDescriptionTransportLength)))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("CreateCategoryResponse"))
}
