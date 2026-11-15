package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import scala.util.Try

/** A positive monetary amount: the validated primitive behind the savings-goal target field.
  *
  * Demonstrates the numeric side of the validated-form stack. The `> 0` rule lives here (not in the form), the typed
  * value is held in the field, and `submitRequest` unwraps it with `.value` rather than parsing a raw string at submit.
  */
class ExpPositiveAmount private (val value: BigDecimal) extends ExpWrappedBigDecimal

object ExpPositiveAmount extends ExpWrappedBigDecimal.Companion[ExpPositiveAmount] {

  override def validate(string: String): ValidationResult[ExpPositiveAmount] = {
    val trimmed = string.trim
    Try(BigDecimal(trimmed)).toOption match {
      case Some(amount) if amount > 0 => ValidationResult.Valid(string, new ExpPositiveAmount(amount))
      case Some(_) => ValidationResult.Invalid(input = string, error = "Amount must be greater than zero")
      case None => ValidationResult.Invalid(input = string, error = "Invalid amount")
    }
  }

  override def trusted(value: BigDecimal): ExpPositiveAmount = new ExpPositiveAmount(value)

  /** An optional amount field: a blank input is valid and means "no amount", any other input must parse and be
    * positive. Supplied as a given so `ValidatedTextInput[Option[ExpPositiveAmount]]` resolves it.
    */
  given optionalValidator: TextValidator[Option[ExpPositiveAmount]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, amount) => ValidationResult.Valid(input, Some(amount))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}
