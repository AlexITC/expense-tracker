package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import java.time.LocalDate
import scala.util.Try

/** Experimental validated date primitive (ISO `YYYY-MM-DD`), mirroring [[ExpWrappedBigDecimal]] so a date plugs into
  * the validated-form stack (`FormField` + `ValidatedTextInput`).
  */
class ExpLocalDate private (val value: LocalDate) {
  override def toString: String = value.toString
}

object ExpLocalDate {
  def validate(string: String): ValidationResult[ExpLocalDate] =
    Try(LocalDate.parse(string.trim)).toOption match {
      case Some(date) => ValidationResult.Valid(string, new ExpLocalDate(date))
      case None => ValidationResult.Invalid(input = string, error = "Date must be in YYYY-MM-DD format")
    }

  def trusted(value: LocalDate): ExpLocalDate = new ExpLocalDate(value)

  given validator: TextValidator[ExpLocalDate] = (string: String) => validate(string)

  def parse(string: String): Either[String, ExpLocalDate] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }

  given optionalValidator: TextValidator[Option[ExpLocalDate]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}
