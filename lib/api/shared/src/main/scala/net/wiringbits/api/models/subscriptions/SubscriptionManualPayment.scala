package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import net.wiringbits.api.models.wrappedStringSchema
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

/** A payment the user recorded by hand for a subscription, for charges that never reach an expense or a statement. */
case class SubscriptionManualPayment(
    id: UUID,
    paidDate: LocalDate,
    amount: BigDecimal,
    note: Option[SubscriptionManualPaymentNote]
)

object SubscriptionManualPayment {
  implicit val format: Format[SubscriptionManualPayment] = Json.format[SubscriptionManualPayment]
  implicit val schema: Schema[SubscriptionManualPayment] =
    Schema.derived[SubscriptionManualPayment].name(Schema.SName("SubscriptionManualPayment"))
}
