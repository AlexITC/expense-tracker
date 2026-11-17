package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.{SubscriptionCadence, SubscriptionTerm}

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.annotation.tailrec

/** Why a subscription's charge dates cannot be computed. */
enum SubscriptionScheduleError(val message: String) {
  case MissingInterval extends SubscriptionScheduleError("Interval days is required for an every-N-days cadence")
  case NonPositiveInterval extends SubscriptionScheduleError("Interval days must be greater than zero")
  case MissingIntervalMonths
      extends SubscriptionScheduleError("Interval months is required for an every-N-months cadence")
  case NonPositiveIntervalMonths extends SubscriptionScheduleError("Interval months must be greater than zero")
}

/** Pure cadence math for a subscription's charge dates.
  *
  * Terms cover half-open ranges [effectiveFrom, effectiveTo). A run of adjacent terms (a price change) keeps one
  * cadence anchor, the start of the run, while a gap (cancel then resume) starts a new anchor at the resumed term.
  * Every occurrence is computed from the anchor, never from the previous occurrence, so month-end clamping does not
  * drift: a 31 Jan anchor gives 28 Feb and then 31 Mar.
  */
object SubscriptionSchedule {

  /** An expected charge date and the term covering it. */
  case class DueDate(date: LocalDate, term: SubscriptionTerm)

  /** The distance between two consecutive occurrences of a cadence. */
  enum Step {
    case Days(n: Int)
    case Months(n: Int)
  }

  def step(
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int]
  ): Either[SubscriptionScheduleError, Step] =
    cadence match {
      case SubscriptionCadence.Monthly => Right(Step.Months(1))
      case SubscriptionCadence.Yearly => Right(Step.Months(12))
      case SubscriptionCadence.EveryNDays =>
        intervalDays match {
          case Some(n) if n > 0 => Right(Step.Days(n))
          case Some(_) => Left(SubscriptionScheduleError.NonPositiveInterval)
          case None => Left(SubscriptionScheduleError.MissingInterval)
        }
      case SubscriptionCadence.EveryNMonths =>
        intervalMonths match {
          case Some(n) if n > 0 => Right(Step.Months(n))
          case Some(_) => Left(SubscriptionScheduleError.NonPositiveIntervalMonths)
          case None => Left(SubscriptionScheduleError.MissingIntervalMonths)
        }
    }

  /** The term whose half-open range [effectiveFrom, effectiveTo) contains `date`. */
  def coveringTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    terms.find(t => !t.effectiveFrom.isAfter(date) && t.effectiveTo.forall(date.isBefore))

  /** The earliest term starting after `date`. */
  def nextTerm(terms: List[SubscriptionTerm], date: LocalDate): Option[SubscriptionTerm] =
    terms.filter(_.effectiveFrom.isAfter(date)).minByOption(_.effectiveFrom.toEpochDay)

  /** The start of the contiguous run of terms containing `term`. */
  def cadenceAnchor(terms: List[SubscriptionTerm], term: SubscriptionTerm): LocalDate = {
    @tailrec
    def loop(from: LocalDate): LocalDate =
      terms.find(_.effectiveTo.contains(from)) match {
        case Some(prev) => loop(prev.effectiveFrom)
        case None => from
      }
    loop(term.effectiveFrom)
  }

  /** The k-th occurrence (k = 0 is the anchor itself). */
  def occurrence(anchor: LocalDate, step: Step, k: Long): LocalDate =
    step match {
      case Step.Days(n) => anchor.plusDays(k * n)
      case Step.Months(n) => anchor.plusMonths(k * n)
    }

  /** The most recent occurrence on or before `today`, or the anchor when it is still in the future. */
  def latestOccurrence(anchor: LocalDate, step: Step, today: LocalDate): LocalDate =
    occurrence(anchor, step, latestIndex(anchor = anchor, step = step, today = today))

  /** The index of [[latestOccurrence]]: 0 when the anchor is still in the future. */
  private def latestIndex(anchor: LocalDate, step: Step, today: LocalDate): Long =
    if (anchor.isAfter(today)) 0L
    else
      step match {
        case Step.Days(n) => ChronoUnit.DAYS.between(anchor, today) / n
        case Step.Months(n) =>
          latestIndexOnOrBefore(
            anchor = anchor,
            step = step,
            today = today,
            k = ChronoUnit.MONTHS.between(anchor, today) / n
          )
      }

  // Bounds the history `dueDates` expands per run of terms, so an ancient start date with a short cadence cannot
  // generate an unbounded number of occurrences: a year of a daily cadence, decades of a monthly one.
  val MaxPastOccurrences: Int = 366

  /** Every covered occurrence on or before `today`, limited to the latest [[MaxPastOccurrences]] of each run of terms,
    * plus the first covered occurrence after `today` if any, in date order. Occurrences exist only inside terms, never
    * in the gaps between them.
    */
  def dueDates(
      terms: List[SubscriptionTerm],
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      today: LocalDate
  ): Either[SubscriptionScheduleError, List[DueDate]] =
    step(cadence = cadence, intervalDays = intervalDays, intervalMonths = intervalMonths).map { step =>
      // Each contiguous run of terms shares the anchor `cadenceAnchor` gives it. Occurrences are generated from the
      // horizon index (computed directly, not by iterating from the anchor) up to the end of the run or the first one
      // after `today`, and kept only where a term of the run covers them, so none fall inside gaps.
      val all = terms
        .groupBy(cadenceAnchor(terms, _))
        .toList
        .sortBy { case (anchor, _) => anchor.toEpochDay }
        .flatMap { case (anchor, run) =>
          val runEnd =
            if (run.exists(_.effectiveTo.isEmpty)) None else run.flatMap(_.effectiveTo).maxByOption(_.toEpochDay)
          // Count back from the run's last covered day when it ended before today, so a long-ended run keeps its
          // latest occurrences instead of losing them all.
          val horizon = runEnd.map(_.minusDays(1)).filter(_.isBefore(today)).getOrElse(today)
          val firstIndex =
            math.max(0L, latestIndex(anchor = anchor, step = step, today = horizon) - MaxPastOccurrences + 1)
          val (past, future) = Iterator
            .iterate(firstIndex)(_ + 1)
            .map(k => occurrence(anchor, step, k))
            .takeWhile(d => runEnd.forall(d.isBefore))
            .span(d => !d.isAfter(today))
          (past ++ future.take(1)).toList.flatMap(d => coveringTerm(run, d).map(DueDate(d, _)))
        }
      val (past, future) = all.partition(d => !d.date.isAfter(today))
      past ++ future.headOption
    }

  // `ChronoUnit.between` can undercount when the start day-of-month is clamped (31 Jan -> 28 Feb counts as 0 months),
  // so step forward while the next occurrence still fits.
  @tailrec
  private def latestIndexOnOrBefore(anchor: LocalDate, step: Step, today: LocalDate, k: Long): Long =
    if (occurrence(anchor, step, k + 1).isAfter(today)) k
    else latestIndexOnOrBefore(anchor = anchor, step = step, today = today, k = k + 1)
}
