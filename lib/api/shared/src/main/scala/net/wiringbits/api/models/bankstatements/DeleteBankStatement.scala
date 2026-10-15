package net.wiringbits.api.models.bankstatements

import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

object DeleteBankStatement {
  case class Response(id: UUID)

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("DeleteBankStatementResponse"))
}
