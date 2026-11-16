package net.wiringbits.ui.forms

import net.wiringbits.api.utils.SubscriptionValidator

class SubscriptionAmount private (val value: BigDecimal) extends ExpWrappedBigDecimal

/** A positive subscription amount bounded to the amount columns' NUMERIC(12,2) shape; the caps come from the shared
  * [[SubscriptionValidator]] so the frontend and the server stay in step.
  */
object SubscriptionAmount
    extends PositiveBigDecimalCompanion[SubscriptionAmount](
      maxIntegerDigits = Some(SubscriptionValidator.MaxAmountIntegerDigits),
      maxDecimals = Some(SubscriptionValidator.MaxAmountScale)
    ) {
  override protected def make(value: BigDecimal): SubscriptionAmount = new SubscriptionAmount(value)
}
