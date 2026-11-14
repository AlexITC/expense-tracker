package net.wiringbits.api.utils

import net.wiringbits.api.models.merchants.{GetImportRules, ImportRuleAction, ImportRuleActionKind}

import java.util.UUID

object ImportRuleMatcher {
  case class Rule(
      id: UUID,
      action: ImportRuleAction,
      pattern: String,
      amount: Option[BigDecimal]
  )

  object Rule {
    def fromApi(r: GetImportRules.Rule): Rule = Rule(r.id, r.action, r.pattern, r.amount)
  }

  // Patterns shorter than this (after normalize) never match.
  val MinPatternLength: Int = 3

  // Rule forms prefill the pattern with the full bank-row description, so the cap leaves room for long ones.
  val MaxPatternLength: Int = 255

  /** A rule description becomes the created expense/refund notes, whose limit is 255 characters. */
  val MaxDescriptionLength: Int = 255

  // The amount column is NUMERIC(12,2): Postgres would silently round anything wider and break the exact match.
  val MaxAmountScale: Int = 2
  val MaxAmountIntegerDigits: Int = 10

  /** Validates a rule before it is stored; shared so the frontend can check the same constraints. */
  def validate(pattern: String, amount: Option[BigDecimal], action: ImportRuleAction): Either[String, Unit] =
    validateFields(pattern, amount, action.descriptionOpt)

  /** Same checks as [[validate]], for callers that have not built the action yet (the rule form validates before it
    * resolves or creates the merchant/category the action points to).
    */
  def validateFields(pattern: String, amount: Option[BigDecimal], description: Option[String]): Either[String, Unit] =
    for {
      _ <- validatePattern(pattern)
      _ <- validateAmount(amount)
      _ <- validateDescription(description)
    } yield ()

  private def validatePattern(pattern: String): Either[String, Unit] =
    if (normalize(pattern).length < MinPatternLength)
      Left(s"Rule pattern must be at least $MinPatternLength characters")
    else if (pattern.length > MaxPatternLength)
      Left(s"Rule pattern must be $MaxPatternLength characters or less")
    else Right(())

  private def validateAmount(amount: Option[BigDecimal]): Either[String, Unit] =
    amount match {
      case Some(a) if a.signum == 0 => Left("Rule amount must not be zero")
      case Some(a) if a.scale > MaxAmountScale =>
        Left(s"Rule amount must have at most $MaxAmountScale decimal places")
      case Some(a) if a.precision - a.scale > MaxAmountIntegerDigits =>
        Left(s"Rule amount must have at most $MaxAmountIntegerDigits integer digits")
      case _ => Right(())
    }

  private def validateDescription(description: Option[String]): Either[String, Unit] =
    description match {
      case Some(d) if d.trim.isEmpty => Left("Rule description must not be blank")
      case Some(d) if d.length > MaxDescriptionLength =>
        Left(s"Rule description must be $MaxDescriptionLength characters or less")
      case _ => Right(())
    }

  def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9]", "")

  /** The explicit rules plus one virtual SetMerchant rule per merchant, using the merchant name as the pattern.
    *
    * Virtual rules are computed, never stored; their id is the merchant id. A merchant whose normalized name equals the
    * pattern of an explicit amount-less SetMerchant rule is skipped, so the explicit rule decides. An explicit rule
    * with an amount does not suppress it: the merchant name must keep matching rows with other amounts.
    */
  def effectiveRules(explicit: List[Rule], merchants: List[(UUID, String)]): List[Rule] = {
    val explicitPatterns =
      explicit.collect {
        case r if r.action.kind == ImportRuleActionKind.SetMerchant && r.amount.isEmpty => normalize(r.pattern)
      }.toSet
    val virtual = merchants.collect {
      case (merchantId, name) if !explicitPatterns.contains(normalize(name)) =>
        Rule(
          merchantId,
          ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None),
          name,
          amount = None
        )
    }
    explicit ++ virtual
  }

  def matches(rule: Rule, text: String): Boolean =
    normalize(text).contains(normalize(rule.pattern))

  /** A rule with an amount matches only when the pattern matches and `amount` equals it exactly (sign included). A rule
    * without an amount ignores `amount`.
    */
  def findMatches(rules: List[Rule], text: String, amount: Option[BigDecimal]): List[Rule] =
    rules.filter { r =>
      normalize(r.pattern).length >= MinPatternLength && matches(r, text) &&
      r.amount.forall(ruleAmount => amount.exists(_.compare(ruleAmount) == 0))
    }

  // More criteria win (pattern + amount beats pattern only), then the longest normalized pattern.
  // Tiebreak: most recently created (UUIDv7 lexicographic order = chronological).
  def findWinner(matchedRules: List[Rule]): Option[Rule] =
    if (matchedRules.isEmpty) None
    else Some(matchedRules.maxBy(r => (criteriaCount(r), normalize(r.pattern).length, r.id.toString)))

  private def criteriaCount(rule: Rule): Int = if (rule.amount.isDefined) 2 else 1

  // For rows: ignore wins over set_merchant, and set_merchant wins over set_category, regardless of pattern length.
  // `amount` is the signed row amount (a charge is negative).
  def findRowWinner(rules: List[Rule], description: String, amount: BigDecimal): Option[Rule] = {
    val matched = findMatches(rules, description, Some(amount))
    def byKind(kind: ImportRuleActionKind): List[Rule] = matched.filter(_.action.kind == kind)
    val ignoreWinner = findWinner(byKind(ImportRuleActionKind.Ignore))
    val merchantWinner = findWinner(byKind(ImportRuleActionKind.SetMerchant))
    val categoryWinner = findWinner(byKind(ImportRuleActionKind.SetCategory))
    ignoreWinner.orElse(merchantWinner).orElse(categoryWinner)
  }

  // For receipts: only set_merchant rules apply. set_category is intentionally row-only — a receipt is a
  // merchant-centric document whose category follows its resolved merchant, so a description-pattern category
  // rule has no role here. Revisit if receipts ever need standalone categorization.
  // `amount` is the receipt total, which is positive, while rule amounts follow the bank-row sign (a charge is
  // negative), so it is compared as a charge: negated. The parser reports an unread total as 0, so 0 counts as
  // unknown. An unknown total (None or 0) never matches a rule with an amount.
  def findReceiptWinner(rules: List[Rule], merchantName: String, amount: Option[BigDecimal]): Option[Rule] =
    findWinner(
      findMatches(
        rules.filter(_.action.kind == ImportRuleActionKind.SetMerchant),
        merchantName,
        amount.filter(_.signum != 0).map(-_)
      )
    )
}
