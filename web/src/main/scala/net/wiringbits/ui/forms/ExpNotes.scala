package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated free-text notes for expense forms, cloned from `CategoryDescription`: the value is stored trimmed and
  * bounded to [[ExpNotes.MaxLength]] characters, so notes plug into the validated-form stack (`FormField` +
  * `ValidatedTextInput`).
  */
class ExpNotes private (val string: String) extends WrappedString

object ExpNotes extends WrappedString.Companion[ExpNotes] {

  val MaxLength: Int = 500

  /** The server caps merchant notes at 255 characters, tighter than [[MaxLength]]. */
  val MerchantMaxLength: Int = 255

  override def validate(string: String): ValidationResult[ExpNotes] = validateWithin(string, maxLength = MaxLength)

  private def validateWithin(string: String, maxLength: Int): ValidationResult[ExpNotes] = {
    val trimmed = string.trim
    if (trimmed.length > maxLength)
      ValidationResult.Invalid(input = string, error = s"Notes must be $maxLength characters or less")
    else ValidationResult.Valid(string, new ExpNotes(trimmed))
  }

  override def trusted(string: String): ExpNotes = new ExpNotes(string)

  def parse(string: String): Either[String, ExpNotes] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }

  /** Blank input means "no notes"; over-limit input is an error. */
  given optionalValidator: TextValidator[Option[ExpNotes]] = OptionalValidator.optional(validate)

  /** Optional notes bounded to [[MerchantMaxLength]], for merchant forms. */
  val merchantOptionalValidator: TextValidator[Option[ExpNotes]] =
    OptionalValidator.optional(validateWithin(_, maxLength = MerchantMaxLength))
}
