package net.wiringbits.common.models

import net.wiringbits.webapp.common.models.WrappedString
import net.wiringbits.webapp.common.validators.ValidationResult

class CategoryDescription private (val string: String) extends WrappedString

object CategoryDescription extends WrappedString.Companion[CategoryDescription] {

  val MaxLength: Int = 500

  override def validate(string: String): ValidationResult[CategoryDescription] = {
    val trimmed = string.trim
    if (trimmed.length > MaxLength)
      ValidationResult.Invalid(input = string, error = s"Description must be $MaxLength characters or less")
    else ValidationResult.Valid(string, new CategoryDescription(trimmed))
  }

  override def trusted(string: String): CategoryDescription = new CategoryDescription(string)

  /** Blank or missing input means "no description"; over-limit input is an error. */
  def parseOptional(raw: Option[String]): Either[String, Option[CategoryDescription]] =
    raw.filterNot(_.isBlank) match {
      case None => Right(None)
      case Some(value) =>
        validate(value) match {
          case ValidationResult.Valid(_, description) => Right(Some(description))
          case ValidationResult.Invalid(_, error) => Left(error)
        }
    }
}
