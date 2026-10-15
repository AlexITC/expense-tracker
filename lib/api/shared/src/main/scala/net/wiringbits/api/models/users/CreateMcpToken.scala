package net.wiringbits.api.models.users

import net.wiringbits.api.models.*
import net.wiringbits.common.models.McpApiToken
import play.api.libs.json.{Format, Json}
import sttp.tapir.{Schema, SchemaType}

object CreateMcpToken {

  case class Response(token: McpApiToken)

  implicit val mcpApiTokenSchema: Schema[McpApiToken] = Schema(SchemaType.SString())
  implicit val createMcpTokenResponseFormat: Format[Response] = Json.format[Response]
  implicit val createMcpTokenResponseSchema: Schema[Response] = Schema
    .derived[Response]
    .name(Schema.SName("CreateMcpTokenResponse"))
    .description("Response with the generated MCP API token")
}
