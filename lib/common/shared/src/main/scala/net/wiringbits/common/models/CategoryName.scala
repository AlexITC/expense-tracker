package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class CategoryName private (val string: String) extends WrappedString

object CategoryName extends WrappedString.Companion[CategoryName] {

  val MaxLength: Int = 25

  override def validate(string: String): ValidationResult[CategoryName] = {
    if (string.isBlank) ValidationResult.Invalid(input = string, error = "The name is required")
    else if (string.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Name must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new CategoryName(string))
  }

  override def trusted(string: String): CategoryName = new CategoryName(string)
}
