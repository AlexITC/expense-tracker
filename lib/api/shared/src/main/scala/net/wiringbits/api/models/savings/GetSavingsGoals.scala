package net.wiringbits.api.models.savings

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.time.Instant
import java.util.UUID

object GetSavingsGoals {
  case class Response(data: List[Response.Goal])

  object Response {
    case class Goal(
        id: UUID,
        name: String,
        targetAmount: Option[BigDecimal],
        balance: BigDecimal,
        progress: Option[BigDecimal],
        createdAt: Instant
    )

    implicit val goalFormat: Format[Goal] = Json.format[Goal]
    implicit val goalSchema: Schema[Goal] = Schema.derived[Goal].name(Schema.SName("GetSavingsGoalsGoal"))
  }

  implicit val format: Format[Response] = Json.format[Response]
  implicit val schema: Schema[Response] = Schema.derived[Response].name(Schema.SName("GetSavingsGoalsResponse"))
}
