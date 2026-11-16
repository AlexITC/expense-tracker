package net.wiringbits.api.models.subscriptions

import play.api.libs.json.*
import sttp.tapir.Schema

/** Where a charge credited to a subscription cycle comes from. */
enum SubscriptionChargeSource(val entryName: String) {
  case Expense extends SubscriptionChargeSource("Expense")
  case BankStatementRow extends SubscriptionChargeSource("BankStatementRow")
  case ManualPayment extends SubscriptionChargeSource("ManualPayment")
}

object SubscriptionChargeSource {
  def fromEntryName(name: String): SubscriptionChargeSource =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown SubscriptionChargeSource: $name"))

  implicit val format: Format[SubscriptionChargeSource] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[SubscriptionChargeSource]](JsError(s"Unknown SubscriptionChargeSource: $s"))(JsSuccess(_))
    }),
    Writes(s => JsString(s.entryName))
  )

  implicit val schema: Schema[SubscriptionChargeSource] = Schema.string[SubscriptionChargeSource]
}
