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

/** The create/edit form of the subscriptions page. Every input is a validated field; `fields` lists only the inputs the
  * selected cadence and amount mode use, so a hidden input never blocks the form. The rules that span several fields
  * come from the shared [[SubscriptionValidator]].
  *
  * `prefix` keeps field names (and thus DOM ids) unique, since the create and edit forms can be on screen together.
  */
case class SubscriptionFormData(
    name: FormField[SubscriptionName],
    cadence: SubscriptionCadence,
    intervalDays: FormField[SubscriptionIntervalDays],
    amountMode: AmountMode,
    exactAmount: FormField[Option[SubscriptionAmount]],
    minAmount: FormField[Option[SubscriptionAmount]],
    maxAmount: FormField[Option[SubscriptionAmount]],
    paymentMethod: SubscriptionPaymentMethod,
    categoryId: FormField[Option[UUID]],
    startDate: FormField[ExpLocalDate]
) extends FormData[CreateSubscription.Request] {

  override def fields: List[FormField[?]] = {
    val intervalFields = cadence match {
      case SubscriptionCadence.EveryNDays => List(intervalDays)
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Nil
    }
    val amountFields = amountMode match {
      case AmountMode.Exact => List(exactAmount)
      case AmountMode.Range => List(minAmount, maxAmount)
    }
    List(name) ++ intervalFields ++ amountFields ++ List(categoryId, startDate)
  }

  override def formValidationErrors: List[String] = {
    // The cross-field rules only make sense once the inputs they check are valid.
    val validatorError = for {
      interval <- resolvedInterval
      (min, max) <- resolvedAmounts
      error <- SubscriptionValidator
        .validate(cadence = cadence, intervalDays = interval, amountMin = min, amountMax = max)
        .left
        .toOption
    } yield error
    super.formValidationErrors ++ validatorError.toList
  }

  override def submitRequest: Option[CreateSubscription.Request] =
    if (!isValid) None
    else
      for {
        validName <- name.valueOpt
        interval <- resolvedInterval
        (min, max) <- resolvedAmounts
        category <- categoryId.valueOpt
        start <- startDate.valueOpt
      } yield CreateSubscription.Request(
        name = validName,
        cadence = cadence,
        intervalDays = interval,
        amountMin = min,
        amountMax = max,
        paymentMethod = paymentMethod,
        categoryId = category,
        startDate = start.value
      )

  def updateRequest: Option[UpdateSubscription.Request] =
    submitRequest.map { request =>
      UpdateSubscription.Request(
        name = request.name,
        cadence = request.cadence,
        intervalDays = request.intervalDays,
        amountMin = request.amountMin,
        amountMax = request.amountMax,
        paymentMethod = request.paymentMethod,
        categoryId = request.categoryId,
        startDate = request.startDate
      )
    }

  // The interval only applies to EveryNDays; for other cadences the (hidden) input is ignored.
  private def resolvedInterval: Option[Option[Int]] =
    cadence match {
      case SubscriptionCadence.EveryNDays => intervalDays.valueOpt.map(days => Some(days.value))
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Some(None)
    }

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

  private def build(
      prefix: String,
      nameLabel: String,
      name: Option[String],
      cadence: SubscriptionCadence,
      intervalDays: Option[String],
      amountMode: AmountMode,
      exactAmount: String,
      minAmount: String,
      maxAmount: String,
      paymentMethod: SubscriptionPaymentMethod,
      categoryId: String,
      startDate: Option[String]
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
      amountMode = amountMode,
      exactAmount = amountField(prefix = prefix, key = "exact-amount", label = "Amount (optional)", raw = exactAmount),
      minAmount = amountField(prefix = prefix, key = "min-amount", label = "Minimum amount", raw = minAmount),
      maxAmount = amountField(prefix = prefix, key = "max-amount", label = "Maximum amount", raw = maxAmount),
      paymentMethod = paymentMethod,
      categoryId = new FormField(
        label = "Category (optional)",
        name = s"$prefix-category",
        required = false,
        value = Some(ExpOptionalId.validate(categoryId))
      ),
      startDate = new FormField(
        label = "Start date (YYYY-MM-DD)",
        name = s"$prefix-start-date",
        required = true,
        value = startDate.map(ExpLocalDate.validate)
      )
    )

  // Blank means "unknown amount", so the field starts valid.
  private def amountField(
      prefix: String,
      key: String,
      label: String,
      raw: String
  ): FormField[Option[SubscriptionAmount]] =
    new FormField(
      label = label,
      name = s"$prefix-$key",
      required = false,
      value = Some(SubscriptionAmount.optionalValidator(raw))
    )

  def initial(nameLabel: String): SubscriptionFormData =
    build(
      prefix = "subscription-create",
      nameLabel = nameLabel,
      name = None,
      cadence = SubscriptionCadence.Monthly,
      intervalDays = None,
      amountMode = AmountMode.Exact,
      exactAmount = "",
      minAmount = "",
      maxAmount = "",
      paymentMethod = SubscriptionPaymentMethod.DirectDebit,
      categoryId = "",
      startDate = None
    )

  def fromSubscription(
      nameLabel: String,
      subscription: GetSubscriptions.Response.Subscription
  ): SubscriptionFormData = {
    val plain: Option[BigDecimal] => String = _.map(_.bigDecimal.toPlainString).getOrElse("")
    val isExact = subscription.amountMin == subscription.amountMax
    build(
      prefix = "subscription-edit",
      nameLabel = nameLabel,
      name = Some(subscription.name),
      cadence = subscription.cadence,
      intervalDays = subscription.intervalDays.map(_.toString),
      amountMode = if (isExact) AmountMode.Exact else AmountMode.Range,
      exactAmount = if (isExact) plain(subscription.amountMin) else "",
      minAmount = if (isExact) "" else plain(subscription.amountMin),
      maxAmount = if (isExact) "" else plain(subscription.amountMax),
      paymentMethod = subscription.paymentMethod,
      categoryId = subscription.categoryId.fold("")(_.toString),
      startDate = Some(subscription.startDate.toString)
    )
  }
}
