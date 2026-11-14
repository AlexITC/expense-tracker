package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction

import java.util.UUID

/** What the import rules decide for a statement-row description. Unlike [[ImportRuleAction]] this adds `NoRule` for a
  * row that no rule matched; a matched row's verdict carries the winning action's data.
  */
object RowRuleVerdict {
  sealed trait Verdict

  case object NoRule extends Verdict
  case object Ignore extends Verdict
  final case class SetMerchant(merchantId: UUID, categoryId: Option[UUID], description: Option[String]) extends Verdict
  final case class SetCategory(categoryId: UUID, description: Option[String]) extends Verdict

  def verdict(rules: List[ImportRuleMatcher.Rule], description: String): Verdict =
    ImportRuleMatcher.findRowWinner(rules, description) match {
      case None => NoRule
      case Some(w) =>
        w.action match {
          case ImportRuleAction.Ignore => Ignore
          case ImportRuleAction.SetMerchant(merchantId, categoryId, description) =>
            SetMerchant(merchantId = merchantId, categoryId = categoryId, description = description)
          case ImportRuleAction.SetCategory(categoryId, description) =>
            SetCategory(categoryId = categoryId, description = description)
        }
    }

  /** The merchant a SetMerchant verdict assigns to the row, if any. */
  def merchantId(verdict: Verdict): Option[UUID] =
    verdict match {
      case s: SetMerchant => Some(s.merchantId)
      case _ => None
    }

  /** The category a SetCategory verdict assigns to the row, if any. */
  def categoryId(verdict: Verdict): Option[UUID] =
    verdict match {
      case s: SetCategory => Some(s.categoryId)
      case _ => None
    }

  /** The merchant a winning SetMerchant rule assigns to the row, if any. */
  def ruleMerchantId(rules: List[ImportRuleMatcher.Rule], description: String): Option[UUID] =
    merchantId(verdict(rules, description))

  /** The category a winning SetCategory rule assigns to the row, if any. */
  def ruleCategoryId(rules: List[ImportRuleMatcher.Rule], description: String): Option[UUID] =
    categoryId(verdict(rules, description))
}
