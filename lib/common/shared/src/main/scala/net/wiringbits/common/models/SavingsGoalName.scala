package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class SavingsGoalName private (val string: String) extends WrappedString

object SavingsGoalName extends WrappedString.Companion[SavingsGoalName] {

  val MaxLength: Int = 100

  override def validate(string: String): ValidationResult[SavingsGoalName] = {
    if (string.isBlank) ValidationResult.Invalid(input = string, error = "The name is required")
    else if (string.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Name must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new SavingsGoalName(string))
  }

  override def trusted(string: String): SavingsGoalName = new SavingsGoalName(string)

  def parse(string: String): Either[String, SavingsGoalName] = ValidationResults.toEither(validate(string))
}
