package net.wiringbits.common.models

import net.wiringbits.webapp.common.validators.ValidationResult

private[models] object ValidationResults {
  def toEither[T](result: ValidationResult[T]): Either[String, T] =
    result match {
      case ValidationResult.Valid(_, value) => Right(value)
      case ValidationResult.Invalid(_, error) => Left(error)
    }
}
