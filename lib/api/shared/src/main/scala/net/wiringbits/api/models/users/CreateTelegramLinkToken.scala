package net.wiringbits.api.models.users

import net.wiringbits.api.models.*
import net.wiringbits.common.models.TelegramLinkToken
import play.api.libs.json.{Format, Json}
import sttp.tapir.{Schema, SchemaType}

object CreateTelegramLinkToken {

  case class Response(token: TelegramLinkToken)

  implicit val telegramLinkTokenSchema: Schema[TelegramLinkToken] = Schema(SchemaType.SString())
  implicit val createTelegramLinkTokenResponseFormat: Format[Response] = Json.format[Response]
  implicit val createTelegramLinkTokenResponseSchema: Schema[Response] = Schema
    .derived[Response]
    .name(Schema.SName("CreateTelegramLinkTokenResponse"))
    .description("Response with the generated Telegram link token")
}
