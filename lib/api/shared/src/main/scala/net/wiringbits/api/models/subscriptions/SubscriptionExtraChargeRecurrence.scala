package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema

/** How often an extra charge repeats from its due date. */
enum SubscriptionExtraChargeRecurrence(val entryName: String) {
  case OneOff extends SubscriptionExtraChargeRecurrence("OneOff")
  case Monthly extends SubscriptionExtraChargeRecurrence("Monthly")
  case Yearly extends SubscriptionExtraChargeRecurrence("Yearly")
}

object SubscriptionExtraChargeRecurrence {
  def fromEntryName(name: String): SubscriptionExtraChargeRecurrence =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown SubscriptionExtraChargeRecurrence: $name"))

  implicit val format: Format[SubscriptionExtraChargeRecurrence] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[SubscriptionExtraChargeRecurrence]](JsError(s"Unknown SubscriptionExtraChargeRecurrence: $s"))(
          JsSuccess(_)
        )
    }),
    Writes(r => JsString(r.entryName))
  )

  implicit val schema: Schema[SubscriptionExtraChargeRecurrence] = Schema.string[SubscriptionExtraChargeRecurrence]
}
