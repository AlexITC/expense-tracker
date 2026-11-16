package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated tax id for merchant forms: the value is stored trimmed and bounded to [[ExpTaxId.MaxLength]] characters,
  * matching the server's 50-char limit, so it plugs into the validated-form stack (`FormField` + `ValidatedTextInput`).
  */
class ExpTaxId private (val string: String) extends WrappedString

object ExpTaxId extends WrappedString.Companion[ExpTaxId] {

  val MaxLength: Int = 50

  override def validate(string: String): ValidationResult[ExpTaxId] = {
    val trimmed = string.trim
    if (trimmed.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Tax ID must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new ExpTaxId(trimmed))
  }

  override def trusted(string: String): ExpTaxId = new ExpTaxId(string)

  def parse(string: String): Either[String, ExpTaxId] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }

  /** Blank input means "no tax id"; over-limit input is an error. */
  given optionalValidator: TextValidator[Option[ExpTaxId]] = OptionalValidator.optional(validate)
}
