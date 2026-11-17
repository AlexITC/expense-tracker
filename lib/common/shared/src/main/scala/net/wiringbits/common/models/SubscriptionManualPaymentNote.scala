package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class SubscriptionManualPaymentNote private (val string: String) extends WrappedString

object SubscriptionManualPaymentNote extends WrappedString.Companion[SubscriptionManualPaymentNote] {

  val MaxLength: Int = 255

  override def validate(string: String): ValidationResult[SubscriptionManualPaymentNote] = {
    val trimmed = string.trim
    if (trimmed.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Note must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new SubscriptionManualPaymentNote(trimmed))
  }

  override def trusted(string: String): SubscriptionManualPaymentNote = new SubscriptionManualPaymentNote(string)

  /** Blank or missing input means "no note"; over-limit input is an error. */
  def parseOptional(raw: Option[String]): Either[String, Option[SubscriptionManualPaymentNote]] =
    raw.filterNot(_.isBlank) match {
      case None => Right(None)
      case Some(value) => ValidationResults.toEither(validate(value)).map(Some(_))
    }
}
