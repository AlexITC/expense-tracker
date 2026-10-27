package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleAction, ImportRuleActionKind}

import java.util.UUID

object ImportRuleMatcher {
  case class Rule(
      id: UUID,
      action: ImportRuleAction,
      pattern: String
  )

  object Rule {
    def fromApi(r: GetImportRules.Rule): Rule = Rule(r.id, r.action, r.pattern)
  }

  // Patterns shorter than this (after normalize) never match.
  val MinPatternLength: Int = 3

  def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9]", "")

  /** The explicit rules plus one virtual SetMerchant rule per merchant, using the merchant name as the pattern.
    *
    * Virtual rules are computed, never stored; their id is the merchant id. A merchant whose normalized name equals the
    * pattern of an explicit SetMerchant rule is skipped, so the explicit rule decides.
    */
  def effectiveRules(explicit: List[Rule], merchants: List[(UUID, String)]): List[Rule] = {
    val explicitPatterns =
      explicit.collect { case r if r.action.kind == ImportRuleActionKind.SetMerchant => normalize(r.pattern) }.toSet
    val virtual = merchants.collect {
      case (merchantId, name) if !explicitPatterns.contains(normalize(name)) =>
        Rule(merchantId, ImportRuleAction.SetMerchant(merchantId), name)
    }
    explicit ++ virtual
  }

  def matches(rule: Rule, text: String): Boolean =
    normalize(text).contains(normalize(rule.pattern))

  def findMatches(rules: List[Rule], text: String): List[Rule] =
    rules.filter(r => normalize(r.pattern).length >= MinPatternLength && matches(r, text))

  // Longest normalized pattern wins. Tiebreak: most recently created (UUIDv7 lexicographic order = chronological).
  def findWinner(matchedRules: List[Rule]): Option[Rule] =
    if (matchedRules.isEmpty) None
    else Some(matchedRules.maxBy(r => (normalize(r.pattern).length, r.id.toString)))

  // For rows: ignore wins over set_merchant, and set_merchant wins over set_category, regardless of pattern length.
  def findRowWinner(rules: List[Rule], description: String): Option[Rule] = {
    val matched = findMatches(rules, description)
    def byKind(kind: ImportRuleActionKind): List[Rule] = matched.filter(_.action.kind == kind)
    val ignoreWinner = findWinner(byKind(ImportRuleActionKind.Ignore))
    val merchantWinner = findWinner(byKind(ImportRuleActionKind.SetMerchant))
    val categoryWinner = findWinner(byKind(ImportRuleActionKind.SetCategory))
    ignoreWinner.orElse(merchantWinner).orElse(categoryWinner)
  }

  // For receipts: only set_merchant rules apply.
  def findReceiptWinner(rules: List[Rule], merchantName: String): Option[Rule] =
    findWinner(findMatches(rules.filter(_.action.kind == ImportRuleActionKind.SetMerchant), merchantName))
}
