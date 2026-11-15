package net.wiringbits.ui.forms

import net.wiringbits.api.utils.ImportRuleMatcher
import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import scala.util.Try

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

object ExpRuleAmount extends ExpWrappedBigDecimal.Companion[ExpRuleAmount] {
  override def validate(string: String): ValidationResult[ExpRuleAmount] =
    Try(BigDecimal(string.trim)).toOption match {
      case None => ValidationResult.Invalid(input = string, error = "Amount must be a number, e.g. 12.50")
      case Some(amount) if amount.signum < 0 =>
        ValidationResult.Invalid(input = string, error = "Rule amount must be a positive value")
      case Some(amount) if amount.signum == 0 =>
        ValidationResult.Invalid(input = string, error = "Rule amount must not be zero")
      case Some(amount) if amount.scale > ImportRuleMatcher.MaxAmountScale =>
        ValidationResult.Invalid(
          input = string,
          error = s"Rule amount must have at most ${ImportRuleMatcher.MaxAmountScale} decimal places"
        )
      case Some(amount) if amount.precision - amount.scale > ImportRuleMatcher.MaxAmountIntegerDigits =>
        ValidationResult.Invalid(
          input = string,
          error = s"Rule amount must have at most ${ImportRuleMatcher.MaxAmountIntegerDigits} integer digits"
        )
      case Some(amount) => ValidationResult.Valid(string, new ExpRuleAmount(amount))
    }

  override def trusted(value: BigDecimal): ExpRuleAmount = new ExpRuleAmount(value)

  given optionalValidator: TextValidator[Option[ExpRuleAmount]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}
