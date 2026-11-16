package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

/** A fee on top of the subscription's terms. dueDate is the one-off date, or the first occurrence of a periodic fee.
  */
case class SubscriptionExtraCharge(
    id: UUID,
    label: String,
    amount: BigDecimal,
    recurrence: SubscriptionExtraChargeRecurrence,
    dueDate: LocalDate
)

object SubscriptionExtraCharge {
  implicit val format: Format[SubscriptionExtraCharge] = Json.format[SubscriptionExtraCharge]
  implicit val schema: Schema[SubscriptionExtraCharge] =
    Schema.derived[SubscriptionExtraCharge].name(Schema.SName("SubscriptionExtraCharge"))
}
