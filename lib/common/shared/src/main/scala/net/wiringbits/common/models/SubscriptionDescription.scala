package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class SubscriptionDescription private (val string: String) extends WrappedString

object SubscriptionDescription extends WrappedString.Companion[SubscriptionDescription] {

  val MaxLength: Int = 500

  override def validate(string: String): ValidationResult[SubscriptionDescription] = {
    val trimmed = string.trim
    if (trimmed.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Description must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new SubscriptionDescription(trimmed))
  }

  override def trusted(string: String): SubscriptionDescription = new SubscriptionDescription(string)

  /** Blank or missing input means "no description"; over-limit input is an error. */
  def parseOptional(raw: Option[String]): Either[String, Option[SubscriptionDescription]] =
    raw.filterNot(_.isBlank) match {
      case None => Right(None)
      case Some(value) => ValidationResults.toEither(validate(value)).map(Some(_))
    }
}
