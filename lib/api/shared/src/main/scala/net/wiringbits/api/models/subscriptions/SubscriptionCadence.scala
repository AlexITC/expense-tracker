package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema

/** How often a subscription is charged. `EveryNDays` takes its interval from the subscription's `intervalDays`. */
enum SubscriptionCadence(val entryName: String) {
  case Monthly extends SubscriptionCadence("Monthly")
  case Yearly extends SubscriptionCadence("Yearly")
  case EveryNDays extends SubscriptionCadence("EveryNDays")
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
