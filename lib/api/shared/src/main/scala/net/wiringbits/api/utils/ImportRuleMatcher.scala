package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.ImportRuleAction

import java.util.UUID

object ImportRuleMatcher {
  case class Rule(id: UUID, action: ImportRuleAction, pattern: String, merchantId: Option[UUID])

  def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9]", "")

  /** The explicit rules plus one virtual SetMerchant rule per merchant, using the merchant name as the pattern.
    *
    * Virtual rules are computed, never stored; their id is the merchant id. A merchant whose normalized name equals the
    * pattern of an explicit SetMerchant rule is skipped, so the explicit rule decides.
    */
  def effectiveRules(explicit: List[Rule], merchants: List[(UUID, String)]): List[Rule] = {
    val explicitPatterns =
      explicit.filter(_.action == ImportRuleAction.SetMerchant).map(r => normalize(r.pattern)).toSet
    val virtual = merchants.collect {
      case (merchantId, name) if !explicitPatterns.contains(normalize(name)) =>
        Rule(merchantId, ImportRuleAction.SetMerchant, name, Some(merchantId))
    }
    explicit ++ virtual
  }

  def matches(rule: Rule, text: String): Boolean =
    normalize(text).contains(normalize(rule.pattern))

  def findMatches(rules: List[Rule], text: String): List[Rule] =
    rules.filter(r => normalize(r.pattern).length >= 3 && matches(r, text))

  // Longest normalized pattern wins. Tiebreak: most recently created (UUIDv7 lexicographic order = chronological).
  def findWinner(matchedRules: List[Rule]): Option[Rule] =
    if (matchedRules.isEmpty) None
    else Some(matchedRules.maxBy(r => (normalize(r.pattern).length, r.id.toString)))

  // For rows: ignore wins over set_merchant when both match.
  def findRowWinner(rules: List[Rule], description: String): Option[Rule] = {
    val matched = findMatches(rules, description)
    val ignoreWinner = findWinner(matched.filter(_.action == ImportRuleAction.Ignore))
    val merchantWinner = findWinner(matched.filter(_.action == ImportRuleAction.SetMerchant))
    ignoreWinner.orElse(merchantWinner)
  }

  // For receipts: only set_merchant rules apply.
  def findReceiptWinner(rules: List[Rule], merchantName: String): Option[Rule] =
    findWinner(findMatches(rules.filter(_.action == ImportRuleAction.SetMerchant), merchantName))
}
