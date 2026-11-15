package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import java.time.LocalTime
import scala.util.Try

/** Validated time-of-day primitive (ISO `HH:MM`, optionally `HH:MM:SS`), mirroring [[ExpLocalDate]] so a time plugs
  * into the validated-form stack (`FormField` + `ValidatedTextInput`).
  */
class ExpLocalTime private (val value: LocalTime) {
  override def toString: String = value.toString
}

object ExpLocalTime {
  def validate(string: String): ValidationResult[ExpLocalTime] =
    Try(LocalTime.parse(string.trim)).toOption match {
      case Some(time) => ValidationResult.Valid(string, new ExpLocalTime(time))
      case None => ValidationResult.Invalid(input = string, error = "Time must be in HH:MM format")
    }

  def trusted(value: LocalTime): ExpLocalTime = new ExpLocalTime(value)

  given validator: TextValidator[ExpLocalTime] = (string: String) => validate(string)

  def parse(string: String): Either[String, ExpLocalTime] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }

  given optionalValidator: TextValidator[Option[ExpLocalTime]] = OptionalValidator.optional(validate)
}
