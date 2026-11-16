package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema

/** How often a subscription is charged.
  *
  * The variants carry no data on purpose: this keeps the phase-1 flat encoding, where the interval of `EveryNDays`
  * lives in the sibling `intervalDays` field and that of `EveryNMonths` in `intervalMonths`. The same flat shape is
  * used by the JSON models and the `subscription` table columns, and `SubscriptionValidator.validateCadence` enforces
  * that each interval is present exactly for its cadence.
  */
enum SubscriptionCadence(val entryName: String) {
  case Monthly extends SubscriptionCadence("Monthly")
  case Yearly extends SubscriptionCadence("Yearly")
  case EveryNDays extends SubscriptionCadence("EveryNDays")
  case EveryNMonths extends SubscriptionCadence("EveryNMonths")
}

object SubscriptionCadence {
  def fromEntryName(name: String): SubscriptionCadence =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown SubscriptionCadence: $name"))

  implicit val format: Format[SubscriptionCadence] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[SubscriptionCadence]](JsError(s"Unknown SubscriptionCadence: $s"))(JsSuccess(_))
    }),
    Writes(c => JsString(c.entryName))
  )

  implicit val schema: Schema[SubscriptionCadence] = Schema.string[SubscriptionCadence]
}
