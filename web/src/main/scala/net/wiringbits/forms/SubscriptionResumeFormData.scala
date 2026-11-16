package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.ResumeSubscription
import net.wiringbits.ui.forms.ExpLocalDate
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

/** Resumes a cancelled subscription with a new term starting on `effectiveFrom`. */
case class SubscriptionResumeFormData(
    effectiveFrom: FormField[ExpLocalDate],
    amounts: SubscriptionAmountFields
) extends FormData[ResumeSubscription.Request] {

  override def fields: List[FormField[?]] = List(effectiveFrom) ++ amounts.fields

  override def formValidationErrors: List[String] = super.formValidationErrors ++ amounts.rangeError.toList

  override def submitRequest: Option[ResumeSubscription.Request] =
    if (!isValid) None
    else
      for {
        from <- effectiveFrom.valueOpt
        (min, max) <- amounts.resolved
      } yield ResumeSubscription.Request(effectiveFrom = from.value, amountMin = min, amountMax = max)
}

object SubscriptionResumeFormData {

  private val Prefix = "subscription-resume"

  def initial: SubscriptionResumeFormData =
    SubscriptionResumeFormData(
      effectiveFrom = new FormField(
        label = "Resume from (YYYY-MM-DD)",
        name = s"$Prefix-effective-from",
        required = true,
        value = None
      ),
      amounts = SubscriptionAmountFields.initial(Prefix)
    )
}
