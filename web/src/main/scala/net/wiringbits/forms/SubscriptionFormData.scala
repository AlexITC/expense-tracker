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
import net.wiringbits.ui.forms.{ExpLocalDate, ExpOptionalId, SubscriptionAmount, SubscriptionIntervalDays}
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.util.UUID

/** Whether the user enters a single expected amount or a min/max range. */
enum AmountMode {
  case Exact, Range
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
    paymentMethod: SubscriptionPaymentMethod,
    categoryId: FormField[Option[UUID]]
) extends FormData[UpdateSubscription.Request] {

  override def fields: List[FormField[?]] = {
    val intervalFields = cadence match {
      case SubscriptionCadence.EveryNDays => List(intervalDays)
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Nil
    }
    List(name) ++ intervalFields ++ List(categoryId)
  }

  override def formValidationErrors: List[String] = {
    // The cross-field rules only make sense once the inputs they check are valid.
    val validatorError = for {
      interval <- resolvedInterval
      error <- SubscriptionValidator.validateCadence(cadence = cadence, intervalDays = interval).left.toOption
    } yield error
    super.formValidationErrors ++ validatorError.toList
  }

  override def submitRequest: Option[UpdateSubscription.Request] =
    if (!isValid) None
    else
      for {
        validName <- name.valueOpt
        interval <- resolvedInterval
        category <- categoryId.valueOpt
      } yield UpdateSubscription.Request(
        name = validName,
        cadence = cadence,
        intervalDays = interval,
        paymentMethod = paymentMethod,
        categoryId = category
      )

  // The interval only applies to EveryNDays; for other cadences the (hidden) input is ignored.
  def resolvedInterval: Option[Option[Int]] =
    cadence match {
      case SubscriptionCadence.EveryNDays => intervalDays.valueOpt.map(days => Some(days.value))
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Some(None)
    }
}

/** The create form: the subscription's own inputs plus its first term (amount and start date). `fields` lists only the
  * amount inputs the selected amount mode uses.
  */
case class CreateSubscriptionFormData(
    details: SubscriptionFormData,
    amountMode: AmountMode,
    exactAmount: FormField[Option[SubscriptionAmount]],
    minAmount: FormField[Option[SubscriptionAmount]],
    maxAmount: FormField[Option[SubscriptionAmount]],
    startDate: FormField[ExpLocalDate]
) extends FormData[CreateSubscription.Request] {

  override def fields: List[FormField[?]] = {
    val amountFields = amountMode match {
      case AmountMode.Exact => List(exactAmount)
      case AmountMode.Range => List(minAmount, maxAmount)
    }
    details.fields ++ amountFields ++ List(startDate)
  }

  override def formValidationErrors: List[String] = {
    // The cross-field rules only make sense once the inputs they check are valid.
    val validatorError = for {
      interval <- details.resolvedInterval
      (min, max) <- resolvedAmounts
      error <- SubscriptionValidator
        .validate(cadence = details.cadence, intervalDays = interval, amountMin = min, amountMax = max)
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
        interval <- details.resolvedInterval
        (min, max) <- resolvedAmounts
        category <- details.categoryId.valueOpt
        start <- startDate.valueOpt
      } yield CreateSubscription.Request(
        name = validName,
        cadence = details.cadence,
        intervalDays = interval,
        amountMin = min,
        amountMax = max,
        paymentMethod = details.paymentMethod,
        categoryId = category,
        startDate = start.value
      )

  private def resolvedAmounts: Option[(Option[BigDecimal], Option[BigDecimal])] =
    amountMode match {
      case AmountMode.Exact =>
        exactAmount.valueOpt.map { amount =>
          val value = amount.map(_.value)
          (value, value)
        }
      case AmountMode.Range =>
        for {
          min <- minAmount.valueOpt
          max <- maxAmount.valueOpt
        } yield (min.map(_.value), max.map(_.value))
    }
}

object SubscriptionFormData {

  private[forms] def build(
      prefix: String,
      nameLabel: String,
      name: Option[String],
      cadence: SubscriptionCadence,
      intervalDays: Option[String],
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
      paymentMethod = subscription.paymentMethod,
      categoryId = subscription.categoryId.fold("")(_.toString)
    )
}

object CreateSubscriptionFormData {

  private val Prefix = "subscription-create"

  // Blank means "unknown amount", so the field starts valid.
  private def amountField(key: String, label: String): FormField[Option[SubscriptionAmount]] =
    new FormField(
      label = label,
      name = s"$Prefix-$key",
      required = false,
      value = Some(SubscriptionAmount.optionalValidator(""))
    )

  def initial(nameLabel: String): CreateSubscriptionFormData =
    CreateSubscriptionFormData(
      details = SubscriptionFormData.build(
        prefix = Prefix,
        nameLabel = nameLabel,
        name = None,
        cadence = SubscriptionCadence.Monthly,
        intervalDays = None,
        paymentMethod = SubscriptionPaymentMethod.DirectDebit,
        categoryId = ""
      ),
      amountMode = AmountMode.Exact,
      exactAmount = amountField(key = "exact-amount", label = "Amount (optional)"),
      minAmount = amountField(key = "min-amount", label = "Minimum amount"),
      maxAmount = amountField(key = "max-amount", label = "Maximum amount"),
      startDate = new FormField(
        label = "Start date (YYYY-MM-DD)",
        name = s"$Prefix-start-date",
        required = true,
        value = None
      )
    )
}
