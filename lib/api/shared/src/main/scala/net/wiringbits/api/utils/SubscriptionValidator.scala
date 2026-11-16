package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.SubscriptionCadence

object SubscriptionValidator {

  // The amount columns are NUMERIC(12,2): extra decimals would be silently rounded and extra integer digits would
  // fail the insert.
  val MaxAmountScale: Int = 2
  val MaxAmountIntegerDigits: Int = 10

  // Caps the term history of one subscription so a single row cannot grow unbounded.
  val MaxTermsPerSubscription: Int = 200

  // Caps the extra charges of one subscription so a single row cannot grow unbounded.
  val MaxExtraChargesPerSubscription: Int = 50

  // Caps the manual payments of one subscription so a single row cannot grow unbounded.
  val MaxManualPaymentsPerSubscription: Int = 500

  // Bounds of an every-N-months cadence: from monthly up to every two years.
  val MinIntervalMonths: Int = 1
  val MaxIntervalMonths: Int = 24

  /** Validates the subscription fields that depend on each other; shared so the frontend can check the same rules. */
  def validate(
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Either[String, Unit] =
    for {
      _ <- validateCadence(cadence = cadence, intervalDays = intervalDays, intervalMonths = intervalMonths)
      _ <- validateAmountRange(amountMin = amountMin, amountMax = amountMax)
    } yield ()

  /** Validates the cadence and its intervals alone, for edits that do not touch the amounts. */
  def validateCadence(
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int]
  ): Either[String, Unit] =
    for {
      _ <- validateIntervalDays(cadence, intervalDays)
      _ <- validateIntervalMonths(cadence, intervalMonths)
    } yield ()

  private def validateIntervalDays(cadence: SubscriptionCadence, intervalDays: Option[Int]): Either[String, Unit] =
    (cadence, intervalDays) match {
      case (SubscriptionCadence.EveryNDays, None) => Left("Interval days is required for an every-N-days cadence")
      case (SubscriptionCadence.EveryNDays, Some(d)) if d <= 0 => Left("Interval days must be greater than zero")
      case (SubscriptionCadence.EveryNDays, Some(_)) => Right(())
      case (_, Some(_)) => Left("Interval days is only allowed for an every-N-days cadence")
      case (_, None) => Right(())
    }

  private def validateIntervalMonths(cadence: SubscriptionCadence, intervalMonths: Option[Int]): Either[String, Unit] =
    (cadence, intervalMonths) match {
      case (SubscriptionCadence.EveryNMonths, None) =>
        Left("Interval months is required for an every-N-months cadence")
      case (SubscriptionCadence.EveryNMonths, Some(m)) if m < MinIntervalMonths || m > MaxIntervalMonths =>
        Left(s"Interval months must be between $MinIntervalMonths and $MaxIntervalMonths")
      case (SubscriptionCadence.EveryNMonths, Some(_)) => Right(())
      case (_, Some(_)) => Left("Interval months is only allowed for an every-N-months cadence")
      case (_, None) => Right(())
    }

  /** Validates one amount range: both bounds or neither, each a positive NUMERIC(12,2), and min ≤ max. */
  def validateAmountRange(amountMin: Option[BigDecimal], amountMax: Option[BigDecimal]): Either[String, Unit] =
    (amountMin, amountMax) match {
      case (None, None) => Right(())
      case (Some(min), Some(max)) =>
        for {
          _ <- validateAmount("Minimum amount", min)
          _ <- validateAmount("Maximum amount", max)
          _ <- if (min > max) Left("Minimum amount must not be greater than the maximum amount") else Right(())
        } yield ()
      case _ => Left("Minimum and maximum amounts must be both set or both empty")
    }

  /** Validates an extra charge amount with the same rules as a term amount. */
  def validateExtraChargeAmount(amount: BigDecimal): Either[String, Unit] =
    validateAmount("Amount", amount)

  /** Validates a manual payment amount with the same rules as a term amount. */
  def validateManualPaymentAmount(amount: BigDecimal): Either[String, Unit] =
    validateAmount("Amount", amount)

  private def validateAmount(label: String, amount: BigDecimal): Either[String, Unit] =
    if (amount.signum <= 0) Left(s"$label must be greater than zero")
    else if (amount.scale > MaxAmountScale) Left(s"$label must have at most $MaxAmountScale decimal places")
    else if (amount.precision - amount.scale > MaxAmountIntegerDigits)
      Left(s"$label must have at most $MaxAmountIntegerDigits integer digits")
    else Right(())
}
