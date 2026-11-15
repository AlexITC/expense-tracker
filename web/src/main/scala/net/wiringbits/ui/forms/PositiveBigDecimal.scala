package net.wiringbits.ui.forms

import net.wiringbits.webapp.common.validators.{TextValidator, ValidationResult}

import scala.util.Try

/** Reusable companion for a positive `BigDecimal` primitive, optionally capped to `maxIntegerDigits` integer digits and
  * `maxDecimals` decimal places. A value that passes is stored at exactly `maxDecimals` scale, so its `toString`
  * renders with that many decimals. Concrete amounts fix the bounds (see [[net.wiringbits.ui.forms.ExpRuleAmount]]);
  * the unbounded [[PositiveBigDecimal]] itself is just "greater than zero".
  */
abstract class PositiveBigDecimalCompanion[T <: ExpWrappedBigDecimal](
    maxIntegerDigits: Option[Int],
    maxDecimals: Option[Int]
) extends ExpWrappedBigDecimal.Companion[T] {
  protected def make(value: BigDecimal): T

  override def validate(string: String): ValidationResult[T] =
    Try(BigDecimal(string.trim)).toOption match {
      case None => ValidationResult.Invalid(input = string, error = "Amount must be a number, e.g. 12.50")
      case Some(amount) if amount.signum <= 0 =>
        ValidationResult.Invalid(input = string, error = "Amount must be greater than zero")
      case Some(amount) =>
        val tooManyDecimals = maxDecimals.filter(amount.scale > _)
        val tooManyIntegerDigits = maxIntegerDigits.filter(amount.precision - amount.scale > _)
        (tooManyDecimals, tooManyIntegerDigits) match {
          case (Some(max), _) =>
            ValidationResult.Invalid(input = string, error = s"Amount must have at most $max decimal places")
          case (_, Some(max)) =>
            ValidationResult.Invalid(input = string, error = s"Amount must have at most $max integer digits")
          case _ =>
            ValidationResult.Valid(string, make(maxDecimals.fold(amount)(decimals => amount.setScale(decimals))))
        }
    }

  override def trusted(value: BigDecimal): T = make(value)

  given optionalValidator: TextValidator[Option[T]] = (string: String) =>
    if (string.trim.isEmpty) ValidationResult.Valid(string, None)
    else
      validate(string) match {
        case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
        case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
      }
}

/** A positive `BigDecimal` with no digit or decimal caps, for the savings goal target. */
class PositiveBigDecimal private (val value: BigDecimal) extends ExpWrappedBigDecimal

object PositiveBigDecimal
    extends PositiveBigDecimalCompanion[PositiveBigDecimal](maxIntegerDigits = None, maxDecimals = None) {
  override protected def make(value: BigDecimal): PositiveBigDecimal = new PositiveBigDecimal(value)
}
