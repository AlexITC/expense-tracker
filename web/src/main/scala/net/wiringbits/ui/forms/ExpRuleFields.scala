package net.wiringbits.ui.forms

import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated primitives for the import-rule form's free-text fields, so pattern/amount/description are validated live
  * in their `FormField`s instead of parsed at submit. The limits come from the shared [[ImportRuleMatcher]] so the
  * frontend and the server stay in step; the per-field checks mirror its (private) validators. In production these
  * would move to `lib/common` and `ImportRuleMatcher` would expose the per-field validators to drop the duplication.
  */
class ExpRulePattern private (val string: String) extends WrappedString

object ExpRulePattern extends WrappedString.Companion[ExpRulePattern] {
  override def validate(string: String): ValidationResult[ExpRulePattern] =
    if (ImportRuleMatcher.normalize(string).length < ImportRuleMatcher.MinPatternLength)
      ValidationResult.Invalid(
        input = string,
        error = s"Rule pattern must be at least ${ImportRuleMatcher.MinPatternLength} characters"
      )
    else if (string.length > ImportRuleMatcher.MaxPatternLength)
      ValidationResult.Invalid(
        input = string,
        error = s"Rule pattern must be ${ImportRuleMatcher.MaxPatternLength} characters or less"
      )
    else ValidationResult.Valid(string, new ExpRulePattern(string))

  override def trusted(string: String): ExpRulePattern = new ExpRulePattern(string)

  /** Blank input means "no rule"; anything else must be a valid pattern. */
  given optionalValidator: TextValidator[Option[ExpRulePattern]] = OptionalValidator.optional(validate)
}

class ExpRuleDescription private (val string: String) extends WrappedString

object ExpRuleDescription extends WrappedString.Companion[ExpRuleDescription] {
  override def validate(string: String): ValidationResult[ExpRuleDescription] =
    if (string.length > ImportRuleMatcher.MaxDescriptionLength)
      ValidationResult.Invalid(
        input = string,
        error = s"Rule description must be ${ImportRuleMatcher.MaxDescriptionLength} characters or less"
      )
    else ValidationResult.Valid(string, new ExpRuleDescription(string))

  override def trusted(string: String): ExpRuleDescription = new ExpRuleDescription(string)

  given optionalValidator: TextValidator[Option[ExpRuleDescription]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}

class ExpRuleAmount private (val value: BigDecimal) extends ExpWrappedBigDecimal

/** A positive amount bounded to the rule column's NUMERIC(12,2) shape, i.e. a [[PositiveBigDecimal]] with the rule
  * caps. Only the bounds differ from any other positive amount, so it just fixes them.
  */
object ExpRuleAmount
    extends PositiveBigDecimalCompanion[ExpRuleAmount](
      maxIntegerDigits = Some(ImportRuleMatcher.MaxAmountIntegerDigits),
      maxDecimals = Some(ImportRuleMatcher.MaxAmountScale)
    ) {
  override protected def make(value: BigDecimal): ExpRuleAmount = new ExpRuleAmount(value)
}
