package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

/** A charge credited to a subscription cycle. `id` is the id of the expense, bank statement row or manual payment, as
  * given by `source`. `amount` is positive (money out).
  */
case class SubscriptionCharge(source: SubscriptionChargeSource, id: UUID, date: LocalDate, amount: BigDecimal)

object SubscriptionCharge {
  implicit val format: Format[SubscriptionCharge] = Json.format[SubscriptionCharge]
  implicit val schema: Schema[SubscriptionCharge] =
    Schema.derived[SubscriptionCharge].name(Schema.SName("SubscriptionCharge"))
}
