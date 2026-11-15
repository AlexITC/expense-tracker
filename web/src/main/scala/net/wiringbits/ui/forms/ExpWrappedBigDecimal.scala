package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

/** Experimental numeric mirror of the library's `WrappedString` base.
  *
  * `WrappedString.Companion` only backs string primitives, so an amount (a `BigDecimal`) cannot reuse it. This clones
  * the same shape for a numeric value: a companion provides `validate`/`parse`/`trusted` plus a `given TextValidator`,
  * so the type plugs into the validated-form stack (`FormField` + `ValidatedTextInput`) exactly like a `WrappedString`
  * primitive. Lives in the app under the `Exp` prefix; the production version would sit in `lib/common` beside the
  * string primitives and also expose a play-json `Format` for the API.
  */
trait ExpWrappedBigDecimal {
  def value: BigDecimal
  override def toString: String = value.toString
}

object ExpWrappedBigDecimal {
  trait Companion[T <: ExpWrappedBigDecimal] {
    def validate(string: String): ValidationResult[T]
    def trusted(value: BigDecimal): T

    given validator: TextValidator[T] = (string: String) => validate(string)

    def parse(string: String): Either[String, T] = validate(string) match {
      case ValidationResult.Valid(_, value) => Right(value)
      case ValidationResult.Invalid(_, error) => Left(error)
    }
  }
}
