package net.wiringbits.api.models.merchants

import play.api.libs.json.*
import sttp.tapir.Schema

/** The kind of an import-rule action, without its data. Used for selectors in the UI and as the discriminator stored in
  * the `action` column. The action itself ([[ImportRuleAction]]) carries the data for its kind.
  */
enum ImportRuleActionKind(val entryName: String) {
  case SetMerchant extends ImportRuleActionKind("set_merchant")
  case SetCategory extends ImportRuleActionKind("set_category")
  case Ignore extends ImportRuleActionKind("ignore")
}

object ImportRuleActionKind {
  def fromEntryName(name: String): ImportRuleActionKind =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown ImportRuleActionKind: $name"))

  implicit val format: Format[ImportRuleActionKind] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[ImportRuleActionKind]](JsError(s"Unknown ImportRuleActionKind: $s"))(JsSuccess(_))
    }),
    Writes(k => JsString(k.entryName))
  )

  implicit val schema: Schema[ImportRuleActionKind] = Schema.string[ImportRuleActionKind]
}
