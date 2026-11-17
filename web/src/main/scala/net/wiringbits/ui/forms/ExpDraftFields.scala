package net.wiringbits.ui.forms

import net.wiringbits.api.utils.BankStatementDraftLimits
import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated primitives for a bank statement draft transaction row. The limits come from the shared
  * [[BankStatementDraftLimits]] so the editor rejects what the server would.
  */
class ExpSignedAmount private (val value: BigDecimal) extends ExpWrappedBigDecimal

/** A statement amount, including zero and negatives (negative = money out), bounded like the server's CSV parser. */
object ExpSignedAmount extends ExpWrappedBigDecimal.Companion[ExpSignedAmount] {
  override def validate(string: String): ValidationResult[ExpSignedAmount] = {
    val trimmed = string.trim
    if (!BankStatementDraftLimits.DecimalPattern.matches(trimmed))
      ValidationResult.Invalid(input = string, error = "Amount must be a number, e.g. -12.34")
    else if (!BankStatementDraftLimits.BoundedDecimalPattern.matches(trimmed))
      ValidationResult.Invalid(
        input = string,
        error = s"Amount must have at most ${BankStatementDraftLimits.MaxAmountIntegerDigits} integer digits and " +
          s"${BankStatementDraftLimits.MaxAmountFractionDigits} decimals"
      )
    else ValidationResult.Valid(string, new ExpSignedAmount(BigDecimal(trimmed)))
  }

  override def trusted(value: BigDecimal): ExpSignedAmount = new ExpSignedAmount(value)

  given optionalValidator: TextValidator[Option[ExpSignedAmount]] = OptionalValidator.optional(validate)
}

class ExpDraftDescription private (val string: String) extends WrappedString

object ExpDraftDescription extends WrappedString.Companion[ExpDraftDescription] {
  override def validate(string: String): ValidationResult[ExpDraftDescription] =
    if (string.isBlank) ValidationResult.Invalid(input = string, error = "Description must not be blank")
    else if (string.length > BankStatementDraftLimits.MaxDescriptionLength)
      ValidationResult.Invalid(
        input = string,
        error = s"Description must be ${BankStatementDraftLimits.MaxDescriptionLength} characters or less"
      )
    else ValidationResult.Valid(string, new ExpDraftDescription(string))

  override def trusted(string: String): ExpDraftDescription = new ExpDraftDescription(string)
}

class ExpDraftTxnType private (val string: String) extends WrappedString

object ExpDraftTxnType extends WrappedString.Companion[ExpDraftTxnType] {
  override def validate(string: String): ValidationResult[ExpDraftTxnType] =
    if (string.length > BankStatementDraftLimits.MaxTxnTypeLength)
      ValidationResult.Invalid(
        input = string,
        error = s"Type must be ${BankStatementDraftLimits.MaxTxnTypeLength} characters or less"
      )
    else ValidationResult.Valid(string, new ExpDraftTxnType(string))

  override def trusted(string: String): ExpDraftTxnType = new ExpDraftTxnType(string)

  /** Blank input means "no type"; over-limit input is an error. */
  given optionalValidator: TextValidator[Option[ExpDraftTxnType]] = OptionalValidator.optional(validate)
}
