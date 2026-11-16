package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{AddSubscriptionExtraCharge, SubscriptionExtraChargeRecurrence}
import net.wiringbits.common.models.SubscriptionExtraChargeLabel
import net.wiringbits.ui.forms.{ExpLocalDate, SubscriptionAmount}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** Adds a fee on top of a subscription's terms; `dueDate` is the one-off date or the first periodic occurrence. */
case class SubscriptionExtraChargeFormData(
    label: FormField[SubscriptionExtraChargeLabel],
    amount: FormField[SubscriptionAmount],
    recurrence: SubscriptionExtraChargeRecurrence,
    dueDate: FormField[ExpLocalDate]
) extends FormData[AddSubscriptionExtraCharge.Request] {

  override def fields: List[FormField[?]] = List(label, amount, dueDate)

  override def submitRequest: Option[AddSubscriptionExtraCharge.Request] =
    if (!isValid) None
    else
      for {
        l <- label.valueOpt
        a <- amount.valueOpt
        d <- dueDate.valueOpt
      } yield AddSubscriptionExtraCharge.Request(
        label = l,
        amount = a.value,
        recurrence = recurrence,
        dueDate = d.value
      )
}

object SubscriptionExtraChargeFormData {

  private val Prefix = "subscription-extra-charge"

  def initial: SubscriptionExtraChargeFormData =
    SubscriptionExtraChargeFormData(
      label = new FormField(label = "Label", name = s"$Prefix-label", required = true, value = None),
      amount = new FormField(label = "Amount", name = s"$Prefix-amount", required = true, value = None),
      recurrence = SubscriptionExtraChargeRecurrence.OneOff,
      dueDate = new FormField(
        label = "Due date (YYYY-MM-DD)",
        name = s"$Prefix-due-date",
        required = true,
        value = None
      )
    )
}
