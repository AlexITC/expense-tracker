package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.AddSubscriptionManualPayment
import net.wiringbits.common.models.SubscriptionManualPaymentNote
import net.wiringbits.ui.forms.{ExpLocalDate, SubscriptionAmount}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** Records a payment made outside the tracked expenses/bank statements so it satisfies a subscription cycle. */
case class SubscriptionManualPaymentFormData(
    paidDate: FormField[ExpLocalDate],
    amount: FormField[SubscriptionAmount],
    note: FormField[Option[SubscriptionManualPaymentNote]]
) extends FormData[AddSubscriptionManualPayment.Request] {

  override def fields: List[FormField[?]] = List(paidDate, amount, note)

  override def submitRequest: Option[AddSubscriptionManualPayment.Request] =
    if (!isValid) None
    else
      for {
        d <- paidDate.valueOpt
        a <- amount.valueOpt
      } yield AddSubscriptionManualPayment.Request(
        paidDate = d.value,
        amount = a.value,
        note = note.valueOpt.flatten
      )
}

object SubscriptionManualPaymentFormData {

  private val Prefix = "subscription-manual-payment"

  def initial: SubscriptionManualPaymentFormData =
    SubscriptionManualPaymentFormData(
      paidDate = new FormField(
        label = "Paid date (YYYY-MM-DD)",
        name = s"$Prefix-paid-date",
        required = true,
        value = None
      ),
      amount = new FormField(label = "Amount", name = s"$Prefix-amount", required = true, value = None),
      note = new FormField(label = "Note", name = s"$Prefix-note", required = false, value = None)
    )
}
