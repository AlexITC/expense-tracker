package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema

/** How a subscription gets paid. */
enum SubscriptionPaymentMethod(val entryName: String) {
  case DirectDebit extends SubscriptionPaymentMethod("DirectDebit")
  case ManualTransfer extends SubscriptionPaymentMethod("ManualTransfer")
  case CardPortal extends SubscriptionPaymentMethod("CardPortal")
  case Cash extends SubscriptionPaymentMethod("Cash")
}

object SubscriptionPaymentMethod {
  def fromEntryName(name: String): SubscriptionPaymentMethod =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown SubscriptionPaymentMethod: $name"))

  implicit val format: Format[SubscriptionPaymentMethod] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[SubscriptionPaymentMethod]](JsError(s"Unknown SubscriptionPaymentMethod: $s"))(JsSuccess(_))
    }),
    Writes(m => JsString(m.entryName))
  )

  implicit val schema: Schema[SubscriptionPaymentMethod] = Schema.string[SubscriptionPaymentMethod]
}
