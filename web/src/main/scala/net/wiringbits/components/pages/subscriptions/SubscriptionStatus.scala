package net.wiringbits.components.pages.subscriptions

import net.wiringbits.api.models.subscriptions.{
  SubscriptionCadence,
  SubscriptionExtraCharge,
  SubscriptionExtraChargeRecurrence,
  SubscriptionPaymentMethod,
  SubscriptionTerm
}

import net.wiringbits.api.utils.{SubscriptionSchedule, SubscriptionScheduleError}

import java.time.LocalDate

enum SubscriptionStatus(val label: String) {
  case Upcoming extends SubscriptionStatus("Upcoming")
  case Due extends SubscriptionStatus("Due")
  case Overdue extends SubscriptionStatus("Overdue")
  case Cancelled extends SubscriptionStatus("Cancelled")
}

/** Why an expected date cannot be computed for a subscription; shared with the server-side schedule. */
type SubscriptionStatusError = SubscriptionScheduleError
val SubscriptionStatusError: SubscriptionScheduleError.type = SubscriptionScheduleError

object SubscriptionStatusLogic {

  /** The term whose half-open range [effectiveFrom, effectiveTo) contains `date`. */
  def coveringTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    SubscriptionSchedule.coveringTerm(terms, date)

  /** The earliest term starting after `date`. */
  def nextTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    SubscriptionSchedule.nextTerm(terms, date)

  /** The term the expected charge belongs to: the one covering `today`, otherwise the next one to start. */
  def expectedTerm(terms: List[SubscriptionTerm], today: LocalDate): Option[SubscriptionTerm] =
    coveringTerm(terms, today).orElse(nextTerm(terms, today))

  /** The start of the contiguous run of terms containing `term`. A price change starts an adjacent term and keeps the
    * billing day, so only a gap (a resume after cancelling) starts a new anchor.
    */
  def cadenceAnchor(terms: List[SubscriptionTerm], term: SubscriptionTerm): LocalDate =
    SubscriptionSchedule.cadenceAnchor(terms, term)

  /** The expected charge date: the latest occurrence of the cadence anchored on the start of the contiguous run of
    * terms covering `today`; when no term covers `today`, the start of the next term. None when no term covers `today`
    * and none starts later, so a cancelled subscription is never overdue.
    */
  def expectedDate(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, Option[LocalDate]] =
    coveringTerm(terms, today) match {
      case Some(term) =>
        latestOccurrence(
          anchor = cadenceAnchor(terms, term),
          cadence = cadence,
          intervalDays = intervalDays,
          intervalMonths = intervalMonths,
          today = today
        )
          .map(Some(_))
      case None => Right(nextTerm(terms, today).map(_.effectiveFrom))
    }

  def status(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, SubscriptionStatus] =
    expectedDate(
      terms = terms,
      cadence = cadence,
      intervalDays = intervalDays,
      intervalMonths = intervalMonths,
      today = today
    ).map {
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
      intervalMonths: Option[Int],
      today: LocalDate
  ): Either[SubscriptionStatusError, LocalDate] =
    SubscriptionSchedule
      .step(cadence = cadence, intervalDays = intervalDays, intervalMonths = intervalMonths)
      .map(step => SubscriptionSchedule.latestOccurrence(anchor = anchor, step = step, today = today))

  def amountLabel(amountMin: Option[BigDecimal], amountMax: Option[BigDecimal]): String =
    (amountMin, amountMax) match {
      case (Some(min), Some(max)) if min == max => min.bigDecimal.toPlainString
      case (Some(min), Some(max)) => s"${min.bigDecimal.toPlainString} - ${max.bigDecimal.toPlainString}"
      case _ => ""
    }

  def cadenceLabel(cadence: SubscriptionCadence, intervalDays: Option[Int], intervalMonths: Option[Int]): String =
    cadence match {
      case SubscriptionCadence.Monthly => "Monthly"
      case SubscriptionCadence.Yearly => "Yearly"
      case SubscriptionCadence.EveryNDays =>
        intervalDays match {
          case Some(1) => "Every day"
          case Some(days) => s"Every $days days"
          case None => "Every N days"
        }
      case SubscriptionCadence.EveryNMonths =>
        intervalMonths match {
          case Some(1) => "Every month"
          case Some(months) => s"Every $months months"
          case None => "Every N months"
        }
    }

  def paymentMethodLabel(method: SubscriptionPaymentMethod): String =
    method match {
      case SubscriptionPaymentMethod.DirectDebit => "Direct debit"
      case SubscriptionPaymentMethod.ManualTransfer => "Manual transfer"
      case SubscriptionPaymentMethod.CardPortal => "Card portal"
      case SubscriptionPaymentMethod.Cash => "Cash"
      case SubscriptionPaymentMethod.CardAutoCharge => "Automatic card charge"
    }

  def extraChargeRecurrenceLabel(recurrence: SubscriptionExtraChargeRecurrence): String =
    recurrence match {
      case SubscriptionExtraChargeRecurrence.OneOff => "One-off"
      case SubscriptionExtraChargeRecurrence.Monthly => "Monthly"
      case SubscriptionExtraChargeRecurrence.Yearly => "Yearly"
    }

  def extraChargeLabel(charge: SubscriptionExtraCharge): String = {
    val schedule = charge.recurrence match {
      case SubscriptionExtraChargeRecurrence.OneOff => s"on ${charge.dueDate}"
      case SubscriptionExtraChargeRecurrence.Monthly | SubscriptionExtraChargeRecurrence.Yearly =>
        s"${extraChargeRecurrenceLabel(charge.recurrence).toLowerCase} from ${charge.dueDate}"
    }
    s"${charge.label} ${charge.amount.bigDecimal.toPlainString} $schedule"
  }
}
