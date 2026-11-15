package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionPaymentMethod, SubscriptionTerm}

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec

enum SubscriptionStatus(val label: String) {
  case Upcoming extends SubscriptionStatus("Upcoming")
  case Due extends SubscriptionStatus("Due")
  case Overdue extends SubscriptionStatus("Overdue")
  case Cancelled extends SubscriptionStatus("Cancelled")
}

/** Why an expected date cannot be computed for a subscription. */
enum SubscriptionStatusError(val message: String) {
  case MissingInterval extends SubscriptionStatusError("Interval days is required for an every-N-days cadence")
  case NonPositiveInterval extends SubscriptionStatusError("Interval days must be greater than zero")
}

object SubscriptionStatusLogic {

  /** The term whose half-open range [effectiveFrom, effectiveTo) contains `date`. */
  def coveringTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    terms.find(t => !t.effectiveFrom.isAfter(date) && t.effectiveTo.forall(date.isBefore))

  /** The earliest term starting after `date`. */
  def nextTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    terms.filter(_.effectiveFrom.isAfter(date)).minByOption(_.effectiveFrom.toEpochDay)

  /** The term the expected charge belongs to: the one covering `today`, otherwise the next one to start. */
  def expectedTerm(terms: List[SubscriptionTerm], today: LocalDate): Option[SubscriptionTerm] =
    coveringTerm(terms, today).orElse(nextTerm(terms, today))

  /** The start of the contiguous run of terms containing `term`. A price change starts an adjacent term and keeps the
    * billing day, so only a gap (a resume after cancelling) starts a new anchor.
    */
  def cadenceAnchor(terms: List[SubscriptionTerm], term: SubscriptionTerm): LocalDate = {
    @tailrec
    def loop(from: LocalDate): LocalDate =
      terms.find(_.effectiveTo.contains(from)) match {
        case Some(prev) => loop(prev.effectiveFrom)
        case None => from
      }
    loop(term.effectiveFrom)
  }

  /** The expected charge date: the latest occurrence of the cadence anchored on the start of the contiguous run of
    * terms covering `today`; when no term covers `today`, the start of the next term. None when no term covers `today`
    * and none starts later, so a cancelled subscription is never overdue.
    */
  def expectedDate(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, Option[LocalDate]] =
    coveringTerm(terms, today) match {
      case Some(term) =>
        latestOccurrence(
          anchor = cadenceAnchor(terms, term),
          cadence = cadence,
          intervalDays = intervalDays,
          today = today
        )
          .map(Some(_))
      case None => Right(nextTerm(terms, today).map(_.effectiveFrom))
    }

  def status(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, SubscriptionStatus] =
    expectedDate(terms = terms, cadence = cadence, intervalDays = intervalDays, today = today).map {
      case None => SubscriptionStatus.Cancelled
      case Some(expected) =>
        if (expected.isAfter(today)) SubscriptionStatus.Upcoming
        else if (expected.isEqual(today)) SubscriptionStatus.Due
        else SubscriptionStatus.Overdue
    }

  /** The most recent occurrence of the cadence on or before `today`, or the anchor when it is still in the future.
    *
    * Every occurrence is computed from `anchor` (never from the previous occurrence) so month-end clamping does not
    * drift: a 31 Jan anchor gives 28 Feb and then 31 Mar.
    */
  def latestOccurrence(
      anchor: LocalDate,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, LocalDate] =
    if (anchor.isAfter(today)) Right(anchor)
    else
      cadence match {
        case SubscriptionCadence.Monthly =>
          Right(latestOnOrBefore(today, ChronoUnit.MONTHS.between(anchor, today), anchor.plusMonths))
        case SubscriptionCadence.Yearly =>
          Right(latestOnOrBefore(today, ChronoUnit.YEARS.between(anchor, today), anchor.plusYears))
        case SubscriptionCadence.EveryNDays =>
          intervalDays match {
            case Some(interval) if interval > 0 =>
              val steps = ChronoUnit.DAYS.between(anchor, today) / interval
              Right(anchor.plusDays(steps * interval))
            case Some(_) => Left(SubscriptionStatusError.NonPositiveInterval)
            case None => Left(SubscriptionStatusError.MissingInterval)
          }
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
