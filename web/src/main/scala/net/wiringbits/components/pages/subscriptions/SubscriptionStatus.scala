package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, SubscriptionTerm}

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec

enum SubscriptionStatus(val label: String) {
  case Upcoming extends SubscriptionStatus("Upcoming")
  case Due extends SubscriptionStatus("Due")
  case Overdue extends SubscriptionStatus("Overdue")
}

/** Why an expected date cannot be computed for a subscription. */
enum SubscriptionStatusError(val message: String) {
  case MissingInterval extends SubscriptionStatusError("Interval days is required for an every-N-days cadence")
  case NonPositiveInterval extends SubscriptionStatusError("Interval days must be greater than zero")
}

object SubscriptionStatusLogic {

  /** The term whose half-open range [effectiveFrom, effectiveTo) covers `today`, otherwise the open term (which then
    * starts in the future). None when every term has ended.
    */
  def currentTerm(terms: List[SubscriptionTerm], today: LocalDate): Option[SubscriptionTerm] =
    terms
      .find(t => !t.effectiveFrom.isAfter(today) && t.effectiveTo.forall(today.isBefore))
      .orElse(terms.find(_.effectiveTo.isEmpty))

  /** The most recent charge date on or before `today`, or the start date when it is still in the future.
    *
    * Every occurrence is computed from `startDate` (never from the previous occurrence) so month-end clamping does not
    * drift: a 31 Jan start gives 28 Feb and then 31 Mar.
    */
  def expectedDate(
      startDate: LocalDate,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, LocalDate] =
    if (startDate.isAfter(today)) Right(startDate)
    else
      cadence match {
        case SubscriptionCadence.Monthly =>
          Right(latestOnOrBefore(today, ChronoUnit.MONTHS.between(startDate, today), startDate.plusMonths))
        case SubscriptionCadence.Yearly =>
          Right(latestOnOrBefore(today, ChronoUnit.YEARS.between(startDate, today), startDate.plusYears))
        case SubscriptionCadence.EveryNDays =>
          intervalDays match {
            case Some(interval) if interval > 0 =>
              val steps = ChronoUnit.DAYS.between(startDate, today) / interval
              Right(startDate.plusDays(steps * interval))
            case Some(_) => Left(SubscriptionStatusError.NonPositiveInterval)
            case None => Left(SubscriptionStatusError.MissingInterval)
          }
      }

  def status(
      startDate: LocalDate,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, SubscriptionStatus] =
    expectedDate(startDate = startDate, cadence = cadence, intervalDays = intervalDays, today = today).map { expected =>
      if (expected.isAfter(today)) SubscriptionStatus.Upcoming
      else if (expected.isEqual(today)) SubscriptionStatus.Due
      else SubscriptionStatus.Overdue
    }

  def amountLabel(amountMin: Option[BigDecimal], amountMax: Option[BigDecimal]): String =
    (amountMin, amountMax) match {
      case (Some(min), Some(max)) if min == max => min.bigDecimal.toPlainString
      case (Some(min), Some(max)) => s"${min.bigDecimal.toPlainString} - ${max.bigDecimal.toPlainString}"
      case _ => ""
    }

  def cadenceLabel(cadence: SubscriptionCadence, intervalDays: Option[Int]): String =
    cadence match {
      case SubscriptionCadence.Monthly => "Monthly"
      case SubscriptionCadence.Yearly => "Yearly"
      case SubscriptionCadence.EveryNDays =>
        intervalDays match {
          case Some(1) => "Every day"
          case Some(days) => s"Every $days days"
          case None => "Every N days"
        }
    }

  def paymentMethodLabel(method: SubscriptionPaymentMethod): String =
    method match {
      case SubscriptionPaymentMethod.DirectDebit => "Direct debit"
      case SubscriptionPaymentMethod.ManualTransfer => "Manual transfer"
      case SubscriptionPaymentMethod.CardPortal => "Card portal"
      case SubscriptionPaymentMethod.Cash => "Cash"
    }

  // `ChronoUnit.between` can undercount when the start day-of-month is clamped (31 Jan -> 28 Feb counts as 0 months),
  // so step forward while the next occurrence still fits.
  @tailrec
  private def latestOnOrBefore(today: LocalDate, steps: Long, occurrence: Long => LocalDate): LocalDate = {
    val next = occurrence(steps + 1)
    if (next.isAfter(today)) occurrence(steps) else latestOnOrBefore(today, steps + 1, occurrence)
  }
}
