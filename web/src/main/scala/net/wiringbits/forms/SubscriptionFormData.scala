package net.wiringbits.forms

import net.wiringbits.api.models.subscriptions.{
  CreateSubscription,
  GetSubscriptions,
  SubscriptionCadence,
  SubscriptionPaymentMethod,
  UpdateSubscription
}
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.SubscriptionValidator
import net.wiringbits.common.models.{MerchantName, SubscriptionDescription, SubscriptionName}
import net.wiringbits.ui.forms.{
  ExistingOrNewValue,
  ExistingSelections,
  ExpLocalDate,
  ExpOptionalId,
  SubscriptionAmount,
  SubscriptionDescriptionValidator,
  SubscriptionIntervalDays,
  SubscriptionIntervalMonths
}
import net.wiringbits.webapp.common.validators.ValidationResult
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
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
  * shared [[SubscriptionValidator]]. The merchant is a pick-or-create selection, so `submitRequest` returns it
  * unresolved; the page resolves it to an id (creating the merchant if needed) and then builds the API request.
  *
  * `prefix` keeps field names (and thus DOM ids) unique, since the create and edit forms can be on screen together.
  */
case class SubscriptionFormData(
    name: FormField[SubscriptionName],
    cadence: SubscriptionCadence,
    intervalDays: FormField[SubscriptionIntervalDays],
    intervalMonths: FormField[SubscriptionIntervalMonths],
    paymentMethod: SubscriptionPaymentMethod,
    merchant: FormField[Option[ExistingOrNewValue[MerchantName, UUID]]],
    categoryId: FormField[Option[UUID]],
    description: FormField[Option[SubscriptionDescription]]
) extends FormData[SubscriptionFormData.Resolved] {

  override def fields: List[FormField[?]] = {
    val intervalFields = cadence match {
      case SubscriptionCadence.EveryNDays => List(intervalDays)
      case SubscriptionCadence.EveryNMonths => List(intervalMonths)
      case SubscriptionCadence.Monthly | SubscriptionCadence.Yearly => Nil
    }
    List(name) ++ intervalFields ++ List(merchant, categoryId, description)
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

  override def submitRequest: Option[SubscriptionFormData.Resolved] =
    if (!isValid) None
    else
      for {
        validName <- name.valueOpt
        intervals <- resolvedIntervals
        selectedMerchant <- merchant.valueOpt
        category <- categoryId.valueOpt
        validDescription <- description.valueOpt
      } yield SubscriptionFormData.Resolved(
        name = validName,
        cadence = cadence,
        intervals = intervals,
        paymentMethod = paymentMethod,
        merchant = selectedMerchant,
        categoryId = category,
        description = validDescription
      )

  /** Points the merchant field at an existing merchant, e.g. one just created from the typed name. */
  def withExistingMerchant(m: GetMerchants.Response.Merchant): SubscriptionFormData =
    copy(merchant = merchant.updated(SubscriptionFormData.asOptional(ExistingSelections.merchant(m))))

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
) extends FormData[CreateSubscriptionFormData.Resolved] {

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

  override def submitRequest: Option[CreateSubscriptionFormData.Resolved] =
    if (!isValid) None
    else
      for {
        resolvedDetails <- details.submitRequest
        (min, max) <- amounts.resolved
        start <- startDate.valueOpt
      } yield CreateSubscriptionFormData.Resolved(
        details = resolvedDetails,
        amountMin = min,
        amountMax = max,
        startDate = start.value
      )
}

object SubscriptionFormData {

  /** A valid edit form whose merchant selection is not yet resolved to an id. */
  case class Resolved(
      name: SubscriptionName,
      cadence: SubscriptionCadence,
      intervals: SubscriptionIntervals,
      paymentMethod: SubscriptionPaymentMethod,
      merchant: Option[ExistingOrNewValue[MerchantName, UUID]],
      categoryId: Option[UUID],
      description: Option[SubscriptionDescription]
  ) {
    def request(merchantId: Option[UUID]): UpdateSubscription.Request =
      UpdateSubscription.Request(
        name = name,
        cadence = cadence,
        intervalDays = intervals.days,
        intervalMonths = intervals.months,
        paymentMethod = paymentMethod,
        merchantId = merchantId,
        categoryId = categoryId,
        description = description
      )
  }

  private[forms] def asOptional[T](result: ValidationResult[T]): ValidationResult[Option[T]] =
    result match {
      case ValidationResult.Valid(input, value) => ValidationResult.Valid(input, Some(value))
      case ValidationResult.Invalid(input, error) => ValidationResult.Invalid(input = input, error = error)
    }

  // A linked merchant missing from the list is shown as an error rather than dropped, so saving never unlinks it
  // silently.
  private def merchantSeed(
      merchantId: Option[UUID],
      merchants: List[GetMerchants.Response.Merchant]
  ): ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]] =
    merchantId match {
      case None => ValidationResult.Valid("", None)
      case Some(id) =>
        merchants.find(_.id == id) match {
          case Some(m) => asOptional(ExistingSelections.merchant(m))
          case None => ValidationResult.Invalid(input = "", error = "The linked merchant was not found")
        }
    }

  private[forms] def build(
      prefix: String,
      nameLabel: String,
      name: Option[String],
      cadence: SubscriptionCadence,
      intervalDays: Option[String],
      intervalMonths: Option[String],
      paymentMethod: SubscriptionPaymentMethod,
      merchant: ValidationResult[Option[ExistingOrNewValue[MerchantName, UUID]]],
      categoryId: String,
      description: String
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
      merchant = new FormField(
        label = "Merchant (optional)",
        name = s"$prefix-merchant",
        required = false,
        value = Some(merchant)
      ),
      categoryId = new FormField(
        label = "Category (optional)",
        name = s"$prefix-category",
        required = false,
        value = Some(ExpOptionalId.validate(categoryId))
      ),
      description = new FormField(
        label = "Description (optional)",
        name = s"$prefix-description",
        required = false,
        value = Some(SubscriptionDescriptionValidator.optionalValidator(description))
      )
    )

  def fromSubscription(
      nameLabel: String,
      subscription: GetSubscriptions.Response.Subscription,
      merchants: List[GetMerchants.Response.Merchant]
  ): SubscriptionFormData =
    build(
      prefix = "subscription-edit",
      nameLabel = nameLabel,
      name = Some(subscription.name),
      cadence = subscription.cadence,
      intervalDays = subscription.intervalDays.map(_.toString),
      intervalMonths = subscription.intervalMonths.map(_.toString),
      paymentMethod = subscription.paymentMethod,
      merchant = merchantSeed(subscription.merchantId, merchants),
      categoryId = subscription.categoryId.fold("")(_.toString),
      description = subscription.description.getOrElse("")
    )
}

object CreateSubscriptionFormData {

  /** A valid create form whose merchant selection is not yet resolved to an id. */
  case class Resolved(
      details: SubscriptionFormData.Resolved,
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal],
      startDate: LocalDate
  ) {
    def request(merchantId: Option[UUID]): CreateSubscription.Request =
      CreateSubscription.Request(
        name = details.name,
        cadence = details.cadence,
        intervalDays = details.intervals.days,
        intervalMonths = details.intervals.months,
        amountMin = amountMin,
        amountMax = amountMax,
        paymentMethod = details.paymentMethod,
        categoryId = details.categoryId,
        merchantId = merchantId,
        description = details.description,
        startDate = startDate
      )
  }

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
        merchant = ValidationResult.Valid("", None),
        categoryId = "",
        description = ""
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
