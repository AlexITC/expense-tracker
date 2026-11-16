package net.wiringbits.api.models.subscriptions

import net.wiringbits.api.models.*
import play.api.libs.json.{Format, Json}
import sttp.tapir.Schema

import java.time.LocalDate
import java.util.UUID

/** A pricing term over the half-open range [effectiveFrom, effectiveTo); `effectiveTo = None` means the term is open.
  * amountMin/amountMax are both empty for an unknown amount, equal for a fixed amount, or a range for a variable one.
  */
case class SubscriptionTerm(
    id: UUID,
    effectiveFrom: LocalDate,
    effectiveTo: Option[LocalDate],
    amountMin: Option[BigDecimal],
    amountMax: Option[BigDecimal]
)

object SubscriptionTerm {
  implicit val format: Format[SubscriptionTerm] = Json.format[SubscriptionTerm]
  implicit val schema: Schema[SubscriptionTerm] =
    Schema.derived[SubscriptionTerm].name(Schema.SName("SubscriptionTerm"))
}
