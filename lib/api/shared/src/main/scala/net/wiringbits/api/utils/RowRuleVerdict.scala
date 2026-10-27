package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction

import java.util.UUID

/** What the import rules decide for a statement-row description. */
object RowRuleVerdict {
  sealed trait Verdict

  case object NoRule extends Verdict
  case object Ignore extends Verdict
  // The merchant id stays optional: a matching SetMerchant rule handles the row even when it has no merchant.
  final case class SetMerchant(merchantId: Option[UUID]) extends Verdict
  final case class SetCategory(categoryId: Option[UUID]) extends Verdict

  def verdict(rules: List[ImportRuleMatcher.Rule], description: String): Verdict =
    ImportRuleMatcher.findRowWinner(rules, description) match {
      case None => NoRule
      case Some(w) =>
        w.action match {
          case ImportRuleAction.Ignore => Ignore
          case ImportRuleAction.SetMerchant => SetMerchant(w.merchantId)
          case ImportRuleAction.SetCategory => SetCategory(w.categoryId)
        }
    }

  /** The merchant a SetMerchant verdict assigns to the row, if any. */
  def merchantId(verdict: Verdict): Option[UUID] =
    verdict match {
      case SetMerchant(merchantId) => merchantId
      case _ => None
    }

  /** The category a SetCategory verdict assigns to the row, if any. */
  def categoryId(verdict: Verdict): Option[UUID] =
    verdict match {
      case SetCategory(categoryId) => categoryId
      case _ => None
    }

  /** The merchant a winning SetMerchant rule assigns to the row, if any. */
  def ruleMerchantId(rules: List[ImportRuleMatcher.Rule], description: String): Option[UUID] =
    merchantId(verdict(rules, description))

  /** The category a winning SetCategory rule assigns to the row, if any. */
  def ruleCategoryId(rules: List[ImportRuleMatcher.Rule], description: String): Option[UUID] =
    categoryId(verdict(rules, description))
}
