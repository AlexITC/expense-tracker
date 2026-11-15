package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{
  CreateSubscription,
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  UpdateSubscription
}
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.common.models.SubscriptionName
import net.wiringbits.ui.forms.{
  ExpLocalDate,
  ExpOptionalId,
  SubscriptionAmount,
  SubscriptionIntervalDays,
  SubscriptionIntervalMonths
}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

/** Whether the user enters a single expected amount or a min/max range. */
enum AmountMode {
  case Exact, Range
}

/** The amount inputs of a term, shared by every form that sets one. `fields` lists only the inputs the selected mode
  * uses; blank means "unknown amount".
  */
case class SubscriptionAmountFields(
    mode: AmountMode,
    exact: FormField[Option[SubscriptionAmount]],
    min: FormField[Option[SubscriptionAmount]],
    max: FormField[Option[SubscriptionAmount]]
) {

  def fields: List[FormField[?]] =
    mode match {
      case AmountMode.Exact => List(exact)
      case AmountMode.Range => List(min, max)
    }

  /** The (min, max) pair to send, once the inputs of the selected mode are valid; an exact amount sends min = max. */
  def resolved: Option[(Option[BigDecimal], Option[BigDecimal])] =
    mode match {
      case AmountMode.Exact =>
        exact.valueOpt.map { amount =>
          val value = amount.map(_.value)
          (value, value)
        }
      case AmountMode.Range =>
        for {
          minValue <- min.valueOpt
          maxValue <- max.valueOpt
        } yield (minValue.map(_.value), maxValue.map(_.value))
    }

  /** The shared cross-field amount rule, checked once the inputs it reads are valid. */
  def rangeError: Option[String] =
    for {
      (minValue, maxValue) <- resolved
      error <- SubscriptionValidator.validateAmountRange(amountMin = minValue, amountMax = maxValue).left.toOption
    } yield error
}

object SubscriptionAmountFields {

  private def field(name: String, label: String): FormField[Option[SubscriptionAmount]] =
    new FormField(
      label = label,
      name = name,
      required = false,
      value = Some(SubscriptionAmount.optionalValidator(""))
    )

  /** Blank amounts in exact mode, so the fields start valid. */
  def initial(prefix: String): SubscriptionAmountFields =
    SubscriptionAmountFields(
      mode = AmountMode.Exact,
      exact = field(name = s"$prefix-exact-amount", label = "Amount (optional)"),
      min = field(name = s"$prefix-min-amount", label = "Minimum amount"),
      max = field(name = s"$prefix-max-amount", label = "Maximum amount")
    )
}

/** The subscription's own inputs, shared by the create and edit forms; on its own it is the edit form, since amounts
  * and dates live in the subscription's terms. Every input is a validated field; `fields` lists only the inputs the
  * selected cadence uses, so a hidden input never blocks the form. The rules that span several fields come from the
  * shared [[SubscriptionValidator]].
  *
  * `prefix` keeps field names (and thus DOM ids) unique, since the create and edit forms can be on screen together.
  */
case class SubscriptionFormData(
    name: FormField[SubscriptionName],
    cadence: SubscriptionCadence,
    intervalDays: FormField[SubscriptionIntervalDays],
    intervalMonths: FormField[SubscriptionIntervalMonths],
    paymentMethod: SubscriptionPaymentMethod,
    categoryId: FormField[Option[UUID]]
) extends FormData[UpdateSubscription.Request] {

  override def fields: List[FormField[?]] = {
    val intervalFields = cadence match {
      case SubscriptionCadence.EveryNDays => List(intervalDays)
      case SubscriptionCadence.EveryNMonths => List(intervalMonths)
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Nil
    }
    List(name) ++ intervalFields ++ List(categoryId)
  }

  override def formValidationErrors: List[String] = {
    // The cross-field rules only make sense once the inputs they check are valid.
    val validatorError = for {
      intervals <- resolvedIntervals
      error <- SubscriptionValidator
        .validateCadence(cadence = cadence, intervalDays = intervals.days, intervalMonths = intervals.months)
        .left
        .toOption
    } yield error
    super.formValidationErrors ++ validatorError.toList
  }

  override def submitRequest: Option[UpdateSubscription.Request] =
    if (!isValid) None
    else
      for {
        validName <- name.valueOpt
        intervals <- resolvedIntervals
        category <- categoryId.valueOpt
      } yield UpdateSubscription.Request(
        name = validName,
        cadence = cadence,
        intervalDays = intervals.days,
        intervalMonths = intervals.months,
        paymentMethod = paymentMethod,
        categoryId = category
      )

  // Each interval only applies to its own cadence; for other cadences the (hidden) input is ignored.
  def resolvedIntervals: Option[SubscriptionIntervals] =
    cadence match {
      case SubscriptionCadence.EveryNDays =>
        intervalDays.valueOpt.map(days => SubscriptionIntervals(days = Some(days.value), months = None))
      case SubscriptionCadence.EveryNMonths =>
        intervalMonths.valueOpt.map(months => SubscriptionIntervals(days = None, months = Some(months.value)))
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly =>
        Some(SubscriptionIntervals(days = None, months = None))
    }
}

/** The interval fields to send for the selected cadence, in the flat encoding of the API models. */
case class SubscriptionIntervals(days: Option[Int], months: Option[Int])

/** The create form: the subscription's own inputs plus its first term (amount and start date). */
case class CreateSubscriptionFormData(
    details: SubscriptionFormData,
    amounts: SubscriptionAmountFields,
    startDate: FormField[ExpLocalDate]
) extends FormData[CreateSubscription.Request] {

  override def fields: List[FormField[?]] = details.fields ++ amounts.fields ++ List(startDate)

  override def formValidationErrors: List[String] = {
    // The cross-field rules only make sense once the inputs they check are valid.
    val validatorError = for {
      intervals <- details.resolvedIntervals
      (min, max) <- amounts.resolved
      error <- SubscriptionValidator
        .validate(
          cadence = details.cadence,
          intervalDays = intervals.days,
          intervalMonths = intervals.months,
          amountMin = min,
          amountMax = max
        )
        .left
        .toOption
    } yield error
    super.formValidationErrors ++ validatorError.toList
  }

  override def submitRequest: Option[CreateSubscription.Request] =
    if (!isValid) None
    else
      for {
        validName <- details.name.valueOpt
        intervals <- details.resolvedIntervals
        (min, max) <- amounts.resolved
        category <- details.categoryId.valueOpt
        start <- startDate.valueOpt
      } yield CreateSubscription.Request(
        name = validName,
        cadence = details.cadence,
        intervalDays = intervals.days,
        intervalMonths = intervals.months,
        amountMin = min,
        amountMax = max,
        paymentMethod = details.paymentMethod,
        categoryId = category,
        startDate = start.value
      )
}

object SubscriptionFormData {

  private[forms] def build(
      prefix: String,
      nameLabel: String,
      name: Option[String],
      cadence: SubscriptionCadence,
      intervalDays: Option[String],
      intervalMonths: Option[String],
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: String
  ): SubscriptionFormData =
    SubscriptionFormData(
      name = new FormField(
        label = nameLabel,
        name = s"$prefix-name",
        required = true,
        value = name.map(SubscriptionName.validate)
      ),
      cadence = cadence,
      intervalDays = new FormField(
        label = "Interval (days)",
        name = s"$prefix-interval",
        required = true,
        value = intervalDays.map(SubscriptionIntervalDays.validate)
      ),
      intervalMonths = new FormField(
        label = "Interval (months)",
        name = s"$prefix-interval-months",
        required = true,
        value = intervalMonths.map(SubscriptionIntervalMonths.validate)
      ),
      paymentMethod = paymentMethod,
      categoryId = new FormField(
        label = "Category (optional)",
        name = s"$prefix-category",
        required = false,
        value = Some(ExpOptionalId.validate(categoryId))
      )
    )

  def fromSubscription(
      nameLabel: String,
      subscription: GetSubscriptions.Response.Subscription
  ): SubscriptionFormData =
    build(
      prefix = "subscription-edit",
      nameLabel = nameLabel,
      name = Some(subscription.name),
      cadence = subscription.cadence,
      intervalDays = subscription.intervalDays.map(_.toString),
      intervalMonths = subscription.intervalMonths.map(_.toString),
      paymentMethod = subscription.paymentMethod,
      categoryId = subscription.categoryId.fold("")(_.toString)
    )
}

object CreateSubscriptionFormData {

  private val Prefix = "subscription-create"

  def initial(nameLabel: String): CreateSubscriptionFormData =
    CreateSubscriptionFormData(
      details = SubscriptionFormData.build(
        prefix = Prefix,
        nameLabel = nameLabel,
        name = None,
        cadence = SubscriptionCadence.Monthly,
        intervalDays = None,
        intervalMonths = None,
        paymentMethod = SubscriptionPaymentMethod.DirectDebit,
        categoryId = ""
      ),
      amounts = SubscriptionAmountFields.initial(Prefix),
      startDate = new FormField(
        label = "Start date (YYYY-MM-DD)",
        name = s"$Prefix-start-date",
        required = true,
        value = None
      )
    )
}
