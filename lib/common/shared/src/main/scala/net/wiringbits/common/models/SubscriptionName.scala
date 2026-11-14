package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class SubscriptionName private (val string: String) extends WrappedString

object SubscriptionName extends WrappedString.Companion[SubscriptionName] {

  val MaxLength: Int = 60

  override def validate(string: String): ValidationResult[SubscriptionName] = {
    if (string.isBlank) ValidationResult.Invalid(input = string, error = "The name is required")
    else if (string.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Name must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new SubscriptionName(string))
  }

  override def trusted(string: String): SubscriptionName = new SubscriptionName(string)

  def parse(string: String): Either[String, SubscriptionName] = ValidationResults.toEither(validate(string))
}
