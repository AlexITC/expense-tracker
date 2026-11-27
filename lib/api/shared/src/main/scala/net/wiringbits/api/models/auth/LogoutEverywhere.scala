package net.wiringbits.api.models.auth

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

object LogoutEverywhere {

  case class Request(noData: String = "")

  case class Response(noData: String = "")

  implicit val logoutEverywhereRequestFormat: Format[Request] = Json.format[Request]
  implicit val logoutEverywhereResponseFormat: Format[Response] = Json.format[Response]

  implicit val logoutEverywhereRequestSchema: Schema[Request] = Schema
    .derived[Request]
    .name(Schema.SName("LogoutEverywhereRequest"))
    .description("Request to log out of every session")
  implicit val logoutEverywhereResponseSchema: Schema[Response] = Schema
    .derived[Response]
    .name(Schema.SName("LogoutEverywhereResponse"))
    .description("Response after logging out of every session")
}
