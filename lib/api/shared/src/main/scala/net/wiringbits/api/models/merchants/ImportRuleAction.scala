package net.wiringbits.api.models.merchants

import play.api.libs.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

/** What an import rule does to a matching row. Each variant carries exactly the data that action needs, so illegal
  * combinations — a set-merchant rule with no merchant, an ignore rule with a category — cannot be represented.
  *
  * The JSON form is a tagged object: `{ "kind": "set_merchant", "merchantId": "..." }`, `{ "kind": "set_category",
  * "categoryId": "..." }`, or `{ "kind": "ignore" }`.
  */
enum ImportRuleAction {
  case SetMerchant(merchantId: UUID)
  case SetCategory(categoryId: UUID)
  case Ignore

  def kind: ImportRuleActionKind = this match {
    case _: SetMerchant => ImportRuleActionKind.SetMerchant
    case _: SetCategory => ImportRuleActionKind.SetCategory
    case Ignore => ImportRuleActionKind.Ignore
  }

  def entryName: String = kind.entryName

  def merchantIdOpt: Option[UUID] = this match {
    case SetMerchant(id) => Some(id)
    case _ => None
  }

  def categoryIdOpt: Option[UUID] = this match {
    case SetCategory(id) => Some(id)
    case _ => None
  }
}

object ImportRuleAction {

  /** Rebuild an action from its stored columns. The write path never persists an inconsistent row, so a missing id for
    * a set_merchant/set_category row is a corrupt row and fails loudly rather than being papered over.
    */
  def fromColumns(action: String, merchantId: Option[UUID], categoryId: Option[UUID]): ImportRuleAction =
    ImportRuleActionKind.fromEntryName(action) match {
      case ImportRuleActionKind.SetMerchant =>
        SetMerchant(merchantId.getOrElse(throw new IllegalStateException(s"set_merchant rule without merchant_id")))
      case ImportRuleActionKind.SetCategory =>
        SetCategory(categoryId.getOrElse(throw new IllegalStateException(s"set_category rule without category_id")))
      case ImportRuleActionKind.Ignore => Ignore
    }

  implicit val format: Format[ImportRuleAction] = Format(
    Reads { js =>
      (js \ "kind").validate[ImportRuleActionKind].flatMap {
        case ImportRuleActionKind.SetMerchant => (js \ "merchantId").validate[UUID].map(SetMerchant.apply)
        case ImportRuleActionKind.SetCategory => (js \ "categoryId").validate[UUID].map(SetCategory.apply)
        case ImportRuleActionKind.Ignore => JsSuccess(Ignore)
      }
    },
    Writes {
      case SetMerchant(id) => Json.obj("kind" -> ImportRuleActionKind.SetMerchant.entryName, "merchantId" -> id)
      case SetCategory(id) => Json.obj("kind" -> ImportRuleActionKind.SetCategory.entryName, "categoryId" -> id)
      case Ignore => Json.obj("kind" -> ImportRuleActionKind.Ignore.entryName)
    }
  )

  implicit val schema: Schema[ImportRuleAction] = Schema.derived[ImportRuleAction]
}
