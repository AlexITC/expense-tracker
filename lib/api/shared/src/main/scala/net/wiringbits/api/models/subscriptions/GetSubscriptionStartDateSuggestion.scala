package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.LocalDate

object GetSubscriptionStartDateSuggestion {
  case class Response(earliestExpenseDate: Option[LocalDate])

  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("GetSubscriptionStartDateSuggestionResponse"))
}
