package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class MerchantName private (val string: String) extends WrappedString

object MerchantName extends WrappedString.Companion[MerchantName] {

  val MaxLength: Int = 100

  override def validate(string: String): ValidationResult[MerchantName] = {
    if (string.isBlank) ValidationResult.Invalid(input = string, error = "The name is required")
    else if (string.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Name must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new MerchantName(string))
  }

  override def trusted(string: String): MerchantName = new MerchantName(string)

  def parse(string: String): Either[String, MerchantName] = ValidationResults.toEither(validate(string))
}
