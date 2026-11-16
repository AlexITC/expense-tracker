package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

/** One expected charge date of a subscription, with the amount range of the term covering it. */
case class SubscriptionCycle(
    dueDate: LocalDate,
    termId: UUID,
    amountMin: Option[BigDecimal],
    amountMax: Option[BigDecimal],
    status: SubscriptionCycleStatus
)

object SubscriptionCycle {
  implicit val format: Format[SubscriptionCycle] = Json.format[SubscriptionCycle]
  implicit val schema: Schema[SubscriptionCycle] =
    Schema.derived[SubscriptionCycle].name(Schema.SName("SubscriptionCycle"))
}
