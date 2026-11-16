package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.CancelSubscription
import net.wiringbits.ui.forms.ExpLocalDate
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** Cancels a subscription: `effectiveTo` is the first date it is no longer charged (the open term's exclusive end). */
case class SubscriptionCancelFormData(
    effectiveTo: FormField[ExpLocalDate]
) extends FormData[CancelSubscription.Request] {

  override def fields: List[FormField[?]] = List(effectiveTo)

  override def submitRequest: Option[CancelSubscription.Request] =
    if (!isValid) None
    else effectiveTo.valueOpt.map(to => CancelSubscription.Request(effectiveTo = to.value))
}

object SubscriptionCancelFormData {

  def initial: SubscriptionCancelFormData =
    SubscriptionCancelFormData(
      effectiveTo = new FormField(
        label = "No charges from (YYYY-MM-DD)",
        name = "subscription-cancel-effective-to",
        required = true,
        value = None
      )
    )
}
