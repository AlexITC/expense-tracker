package net.wiringbits.api.models.merchants

import play.api.libs.json.*
import sttp.tapir.Schema

sealed abstract class ImportRuleAction(val entryName: String)

object ImportRuleAction {
  case object SetMerchant extends ImportRuleAction("set_merchant")
  case object Ignore extends ImportRuleAction("ignore")
  case object SetCategory extends ImportRuleAction("set_category")

  val values: Seq[ImportRuleAction] = Seq(SetMerchant, Ignore, SetCategory)

  def fromEntryName(name: String): ImportRuleAction =
    values
      .find(_.entryName == name)
      .getOrElse(throw new NoSuchElementException(s"Unknown ImportRuleAction: $name"))

  implicit val format: Format[ImportRuleAction] = Format(
    Reads(_.validate[String].flatMap { s =>
      values
        .find(_.entryName == s)
        .fold[JsResult[ImportRuleAction]](JsError(s"Unknown action: $s"))(JsSuccess(_))
    }),
    Writes(a => JsString(a.entryName))
  )

  implicit val schema: Schema[ImportRuleAction] = Schema.string[ImportRuleAction]
}
