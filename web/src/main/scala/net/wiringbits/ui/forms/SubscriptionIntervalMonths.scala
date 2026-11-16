package net.wiringbits.ui.forms

import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Validated primitive for the every-N-months interval of a subscription: a whole number of months within the bounds of
  * [[SubscriptionValidator]]. Mirrors [[SubscriptionIntervalDays]] so it plugs into the validated-form stack.
  */
class SubscriptionIntervalMonths private (val value: Int) {
  override def toString: String = value.toString
}

object SubscriptionIntervalMonths {
  def validate(string: String): ValidationResult[SubscriptionIntervalMonths] =
    string.trim.toIntOption match {
      case None => ValidationResult.Invalid(input = string, error = "Interval months must be a whole number")
      case Some(months)
          if months < SubscriptionValidator.MinIntervalMonths || months > SubscriptionValidator.MaxIntervalMonths =>
        ValidationResult.Invalid(
          input = string,
          error =
            s"Interval months must be between ${SubscriptionValidator.MinIntervalMonths} and ${SubscriptionValidator.MaxIntervalMonths}"
        )
      case Some(months) => ValidationResult.Valid(string, new SubscriptionIntervalMonths(months))
    }

  given validator: TextValidator[SubscriptionIntervalMonths] = (string: String) => validate(string)

  def parse(string: String): Either[String, SubscriptionIntervalMonths] = validate(string) match {
    case ValidationResult.Valid(_, value) => Right(value)
    case ValidationResult.Invalid(_, error) => Left(error)
  }
}
