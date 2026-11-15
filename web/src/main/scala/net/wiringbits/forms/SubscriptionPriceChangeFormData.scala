package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.ChangeSubscriptionPrice
import net.wiringbits.ui.forms.ExpLocalDate
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** Starts a new price from `effectiveFrom`, closing the current term on that date. */
case class SubscriptionPriceChangeFormData(
    effectiveFrom: FormField[ExpLocalDate],
    amounts: SubscriptionAmountFields
) extends FormData[ChangeSubscriptionPrice.Request] {

  override def fields: List[FormField[?]] = List(effectiveFrom) ++ amounts.fields

  override def formValidationErrors: List[String] = super.formValidationErrors ++ amounts.rangeError.toList

  override def submitRequest: Option[ChangeSubscriptionPrice.Request] =
    if (!isValid) None
    else
      for {
        from <- effectiveFrom.valueOpt
        (min, max) <- amounts.resolved
      } yield ChangeSubscriptionPrice.Request(effectiveFrom = from.value, amountMin = min, amountMax = max)
}

object SubscriptionPriceChangeFormData {

  private val Prefix = "subscription-price-change"

  def initial: SubscriptionPriceChangeFormData =
    SubscriptionPriceChangeFormData(
      effectiveFrom = new FormField(
        label = "New price from (YYYY-MM-DD)",
        name = s"$Prefix-effective-from",
        required = true,
        value = None
      ),
      amounts = SubscriptionAmountFields.initial(Prefix)
    )
}
