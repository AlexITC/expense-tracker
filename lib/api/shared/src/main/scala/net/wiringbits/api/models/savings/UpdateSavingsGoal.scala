package net.wiringbits.api.models.savings

import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.SavingsGoalName
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

object UpdateSavingsGoal {
  // name is a validated primitive: its length limit and messages live in SavingsGoalName and are enforced at decode by
  // its Format, so this model carries no separate cap.
  case class Request(name: SavingsGoalName, targetAmount: Option[BigDecimal])
  case class Response()

  implicit val requestFormat: Format[Request] = Json.format[Request]
  implicit val requestSchema: Schema[Request] = Schema.derived[Request].name(Schema.SName("UpdateSavingsGoalRequest"))
  implicit val responseFormat: Format[Response] = Json.format[Response]
  implicit val responseSchema: Schema[Response] =
    Schema.derived[Response].name(Schema.SName("UpdateSavingsGoalResponse"))
}
