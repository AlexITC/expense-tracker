package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Single owner of the "optional field" rule for validated primitives: blank input means no value, anything else is
  * validated and wrapped in `Some`.
  */
object OptionalValidator {
  def optional[T](validate: String => ValidationResult[T]): TextValidator[Option[T]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}
