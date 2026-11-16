package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated primitive for the every-N-days interval of a subscription: a whole number of days greater than zero.
  * Mirrors [[ExpWrappedBigDecimal]] so it plugs into the validated-form stack (`FormField` + `ValidatedTextInput`).
  */
class SubscriptionIntervalDays private (val value: Int) {
  override def toString: String = value.toString
}

object SubscriptionIntervalDays {
  def validate(string: String): ValidationResult[SubscriptionIntervalDays] =
    string.trim.toIntOption match {
      case None => ValidationResult.Invalid(input = string, error = "Interval days must be a whole number")
      case Some(days) if days <= 0 =>
        ValidationResult.Invalid(input = string, error = "Interval days must be greater than zero")
      case Some(days) => ValidationResult.Valid(string, new SubscriptionIntervalDays(days))
    }

  def trusted(value: Int): SubscriptionIntervalDays = new SubscriptionIntervalDays(value)

  given validator: TextValidator[SubscriptionIntervalDays] = (string: String) => validate(string)

  def parse(string: String): Either[String, SubscriptionIntervalDays] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }
}
