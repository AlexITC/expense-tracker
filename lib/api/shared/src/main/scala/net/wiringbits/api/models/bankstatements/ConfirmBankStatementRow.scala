package net.wiringbits.api.models.bankstatements

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.{Instant, LocalDate}

object ConfirmBankStatementRow {
  case class Request(date: LocalDate, amount: BigDecimal, description: String)
  case class Response(confirmedAt: Instant)

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val responseFormat: Format[Response] = Json.format[Response]

  implicit val requestSchema: Schema[Request] =
    Schema.derived[Request].name(Schema.SName("ConfirmBankStatementRowRequest"))
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("ConfirmBankStatementRowResponse"))
}
