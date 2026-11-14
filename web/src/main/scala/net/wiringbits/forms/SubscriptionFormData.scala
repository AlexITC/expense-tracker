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
import net.wiringbits.webapp.utils.slinkyUtils.forms.{FormData, FormField}

import java.time.LocalDate
import java.util.UUID
import scala.util.Try

/** Whether the user enters a single expected amount or a min/max range. */
enum AmountMode {
  case Exact, Range
}

case class SubscriptionFormData(
    name: FormField[SubscriptionName],
    cadence: SubscriptionCadence,
    intervalDays: String,
    amountMode: AmountMode,
    exactAmount: String,
    minAmount: String,
    maxAmount: String,
    paymentMethod: SubscriptionPaymentMethod,
    categoryInput: String,
    categoryId: Option[UUID],
    startDate: String
) extends FormData[CreateSubscription.Request] {
  import SubscriptionFormData.*

  override def fields: List[FormField[?]] = List(name)

  override def formValidationErrors: List[String] = {
    val parseErrors = List(parsedInterval, parsedAmounts, parsedStartDate, parsedCategoryId).flatMap(_.left.toOption)
    // The cross-field rules only make sense once the inputs they check have been parsed.
    val validatorError = for {
      interval <- parsedInterval.toOption
      amounts <- parsedAmounts.toOption
      error <- SubscriptionValidator
        .validate(cadence = cadence, intervalDays = interval, amountMin = amounts.min, amountMax = amounts.max)
        .left
        .toOption
    } yield error
    List(fieldsError).flatten ++ parseErrors ++ validatorError.toList
  }

  override def submitRequest: Option[CreateSubscription.Request] =
    if (formValidationErrors.nonEmpty) None
    else
      for {
        validName <- name.valueOpt
        interval <- parsedInterval.toOption
        amounts <- parsedAmounts.toOption
        start <- parsedStartDate.toOption
        category <- parsedCategoryId.toOption
      } yield CreateSubscription.Request(
        name = validName,
        cadence = cadence,
        intervalDays = interval,
        amountMin = amounts.min,
        amountMax = amounts.max,
        paymentMethod = paymentMethod,
        categoryId = category,
        startDate = start
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
  private def parsedInterval: Either[String, Option[Int]] =
    if (cadence != SubscriptionCadence.EveryNDays || intervalDays.isBlank) Right(None)
    else intervalDays.trim.toIntOption.toRight("Interval days must be a whole number").map(Some(_))

  private def parsedAmounts: Either[String, Amounts] =
    amountMode match {
      case AmountMode.Exact =>
        parseAmount(label = "Amount", input = exactAmount).map(amount => Amounts(min = amount, max = amount))
      case AmountMode.Range =>
        for {
          min <- parseAmount(label = "Minimum amount", input = minAmount)
          max <- parseAmount(label = "Maximum amount", input = maxAmount)
        } yield Amounts(min = min, max = max)
    }

  private def parsedStartDate: Either[String, LocalDate] =
    if (startDate.isBlank) Left("Start date is required")
    else Try(LocalDate.parse(startDate.trim)).toOption.toRight("Start date must be a date in YYYY-MM-DD format")

  // Typed text without a picked option is rejected: subscriptions never create categories implicitly.
  private def parsedCategoryId: Either[String, Option[UUID]] =
    categoryId match {
      case Some(id) => Right(Some(id))
      case None if categoryInput.isBlank => Right(None)
      case None => Left("Pick a category from the list")
    }
}

object SubscriptionFormData {

  private case class Amounts(min: Option[BigDecimal], max: Option[BigDecimal])

  // Blank means "unknown amount"; anything else must be a number.
  private def parseAmount(label: String, input: String): Either[String, Option[BigDecimal]] =
    if (input.isBlank) Right(None)
    else Try(BigDecimal(input.trim)).toOption.toRight(s"$label must be a number").map(Some(_))

  def initial(nameLabel: String): SubscriptionFormData = SubscriptionFormData(
    name = nameField(nameLabel, None),
    cadence = SubscriptionCadence.Monthly,
    intervalDays = "",
    amountMode = AmountMode.Exact,
    exactAmount = "",
    minAmount = "",
    maxAmount = "",
    paymentMethod = SubscriptionPaymentMethod.DirectDebit,
    categoryInput = "",
    categoryId = None,
    startDate = ""
  )

  def fromSubscription(
      nameLabel: String,
      subscription: GetSubscriptions.Response.Subscription,
      categoryName: Option[String]
  ): SubscriptionFormData = {
    val plain: Option[BigDecimal] => String = _.map(_.bigDecimal.toPlainString).getOrElse("")
    val isExact = subscription.amountMin == subscription.amountMax
    SubscriptionFormData(
      name = nameField(nameLabel, Some(subscription.name)),
      cadence = subscription.cadence,
      intervalDays = subscription.intervalDays.map(_.toString).getOrElse(""),
      amountMode = if (isExact) AmountMode.Exact else AmountMode.Range,
      exactAmount = if (isExact) plain(subscription.amountMin) else "",
      minAmount = if (isExact) "" else plain(subscription.amountMin),
      maxAmount = if (isExact) "" else plain(subscription.amountMax),
      paymentMethod = subscription.paymentMethod,
      categoryInput = categoryName.getOrElse(""),
      categoryId = subscription.categoryId,
      startDate = subscription.startDate.toString
    )
  }

  private def nameField(label: String, value: Option[String]): FormField[SubscriptionName] =
    new FormField(
      label = label,
      name = "name",
      required = true,
      value = value.map(SubscriptionName.validate)
    )
}
