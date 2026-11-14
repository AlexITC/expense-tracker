package net.wiringbits.api.models.merchants

import play.api.libs.json.*
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*

import java.util.UUID

/** What an import rule does to a matching row. Each variant carries exactly the data that action needs, so illegal
  * combinations — a set-merchant rule with no merchant, an ignore rule with a category — cannot be represented.
  *
  * A set-merchant rule may also override the merchant's default category (`categoryId`), and set-merchant/set-category
  * rules may carry a `description` for the created record.
  *
  * The JSON form is a tagged object: `{ "kind": "set_merchant", "merchantId": "...", "categoryId"?: "...",
  * "description"?: "..." }`, `{ "kind": "set_category", "categoryId": "...", "description"?: "..." }`, or `{ "kind":
  * "ignore" }`. Absent optional keys mean None.
  */
enum ImportRuleAction {
  case SetMerchant(merchantId: UUID, categoryId: Option[UUID], description: Option[String])
  case SetCategory(categoryId: UUID, description: Option[String])
  case Ignore

  def kind: ImportRuleActionKind = this match {
    case _: SetMerchant => ImportRuleActionKind.SetMerchant
    case _: SetCategory => ImportRuleActionKind.SetCategory
    case Ignore => ImportRuleActionKind.Ignore
  }

  def entryName: String = kind.entryName

  def merchantIdOpt: Option[UUID] = this match {
    case s: SetMerchant => Some(s.merchantId)
    case _ => None
  }

  /** The set_category target (the `category_id` column); a set-merchant override is [[overrideCategoryIdOpt]]. */
  def categoryIdOpt: Option[UUID] = this match {
    case s: SetCategory => Some(s.categoryId)
    case _ => None
  }

  /** The category a set-merchant rule uses instead of the merchant's default (the `override_category_id` column). */
  def overrideCategoryIdOpt: Option[UUID] = this match {
    case s: SetMerchant => s.categoryId
    case _ => None
  }

  def descriptionOpt: Option[String] = this match {
    case s: SetMerchant => s.description
    case s: SetCategory => s.description
    case Ignore => None
  }
}

object ImportRuleAction {

  /** A rule description becomes the created expense/refund notes, whose limit is 255 characters. */
  val MaxDescriptionLength: Int = 255

  /** Fails when the action's description exceeds [[MaxDescriptionLength]]. */
  def validateDescription(action: ImportRuleAction): Either[String, Unit] =
    if (action.descriptionOpt.exists(_.length > MaxDescriptionLength))
      Left(s"Rule description must be $MaxDescriptionLength characters or less")
    else Right(())

  /** Rebuild an action from its stored columns. The write path never persists an inconsistent row, so a missing id for
    * a set_merchant/set_category row is a corrupt row and fails loudly rather than being papered over.
    */
  def fromColumns(
      action: String,
      merchantId: Option[UUID],
      categoryId: Option[UUID],
      overrideCategoryId: Option[UUID],
      description: Option[String]
  ): ImportRuleAction =
    ImportRuleActionKind.fromEntryName(action) match {
      case ImportRuleActionKind.SetMerchant =>
        SetMerchant(
          merchantId = merchantId.getOrElse(throw new IllegalStateException(s"set_merchant rule without merchant_id")),
          categoryId = overrideCategoryId,
          description = description
        )
      case ImportRuleActionKind.SetCategory =>
        SetCategory(
          categoryId = categoryId.getOrElse(throw new IllegalStateException(s"set_category rule without category_id")),
          description = description
        )
      case ImportRuleActionKind.Ignore => Ignore
    }

  implicit val format: Format[ImportRuleAction] = Format(
    Reads { js =>
      (js \ "kind").validate[ImportRuleActionKind].flatMap {
        case ImportRuleActionKind.SetMerchant =>
          for {
            merchantId <- (js \ "merchantId").validate[UUID]
            categoryId <- (js \ "categoryId").validateOpt[UUID]
            description <- (js \ "description").validateOpt[String]
          } yield SetMerchant(merchantId = merchantId, categoryId = categoryId, description = description)
        case ImportRuleActionKind.SetCategory =>
          for {
            categoryId <- (js \ "categoryId").validate[UUID]
            description <- (js \ "description").validateOpt[String]
          } yield SetCategory(categoryId = categoryId, description = description)
        case ImportRuleActionKind.Ignore => JsSuccess(Ignore)
      }
    },
    Writes {
      case SetMerchant(merchantId, categoryId, description) =>
        Json.obj("kind" -> ImportRuleActionKind.SetMerchant.entryName, "merchantId" -> merchantId) ++
          optional("categoryId", categoryId) ++ optional("description", description)
      case SetCategory(categoryId, description) =>
        Json.obj("kind" -> ImportRuleActionKind.SetCategory.entryName, "categoryId" -> categoryId) ++
          optional("description", description)
      case Ignore => Json.obj("kind" -> ImportRuleActionKind.Ignore.entryName)
    }
  )

  private def optional[A: Writes](key: String, value: Option[A]): JsObject =
    value.fold(Json.obj())(v => Json.obj(key -> v))

  implicit val schema: Schema[ImportRuleAction] = Schema.derived[ImportRuleAction]
}
