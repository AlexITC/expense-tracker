package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.SubscriptionCadence

object SubscriptionValidator {

  // The amount columns are NUMERIC(12,2): extra decimals would be silently rounded and extra integer digits would
  // fail the insert.
  val MaxAmountScale: Int = 2
  val MaxAmountIntegerDigits: Int = 10

  /** Validates the subscription fields that depend on each other; shared so the frontend can check the same rules. */
  def validate(
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      amountMin: Option[BigDecimal],
      amountMax: Option[BigDecimal]
  ): Either[String, Unit] =
    for {
      _ <- validateInterval(cadence, intervalDays)
      _ <- validateAmountRange(amountMin = amountMin, amountMax = amountMax)
    } yield ()

  private def validateInterval(cadence: SubscriptionCadence, intervalDays: Option[Int]): Either[String, Unit] =
    (cadence, intervalDays) match {
      case (SubscriptionCadence.EveryNDays, None) => Left("Interval days is required for an every-N-days cadence")
      case (SubscriptionCadence.EveryNDays, Some(d)) if d <= 0 => Left("Interval days must be greater than zero")
      case (SubscriptionCadence.EveryNDays, Some(_)) => Right(())
      case (_, Some(_)) => Left("Interval days is only allowed for an every-N-days cadence")
      case (_, None) => Right(())
    }

  private def validateAmountRange(amountMin: Option[BigDecimal], amountMax: Option[BigDecimal]): Either[String, Unit] =
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

  private def validateAmount(label: String, amount: BigDecimal): Either[String, Unit] =
    if (amount.signum <= 0) Left(s"$label must be greater than zero")
    else if (amount.scale > MaxAmountScale) Left(s"$label must have at most $MaxAmountScale decimal places")
    else if (amount.precision - amount.scale > MaxAmountIntegerDigits)
      Left(s"$label must have at most $MaxAmountIntegerDigits integer digits")
    else Right(())
}
