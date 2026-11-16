package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class SubscriptionExtraChargeLabel private (val string: String) extends WrappedString

object SubscriptionExtraChargeLabel extends WrappedString.Companion[SubscriptionExtraChargeLabel] {

  val MaxLength: Int = 100

  override def validate(string: String): ValidationResult[SubscriptionExtraChargeLabel] = {
    val trimmed = string.trim
    if (trimmed.isEmpty) ValidationResult.Invalid(input = string, error = "The label is required")
    else if (trimmed.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Label must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new SubscriptionExtraChargeLabel(trimmed))
  }

  override def trusted(string: String): SubscriptionExtraChargeLabel = new SubscriptionExtraChargeLabel(string)
}
