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

  def verdict(rules: List[ImportRuleMatcher.Rule], description: String): Verdict =
    ImportRuleMatcher.findRowWinner(rules, description) match {
      case None => NoRule
      case Some(w) if w.action == ImportRuleAction.Ignore => Ignore
      case Some(w) => SetMerchant(w.merchantId)
    }

  /** The merchant a winning SetMerchant rule assigns to the row, if any. */
  def ruleMerchantId(rules: List[ImportRuleMatcher.Rule], description: String): Option[UUID] =
    verdict(rules, description) match {
      case SetMerchant(merchantId) => merchantId
      case _ => None
    }
}
