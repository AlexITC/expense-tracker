package net.wiringbits.api.utils

import net.wiringbits.api.models.subscriptions.*
import net.wiringbits.api.utils.DateAmountMatcher.{Candidate, Slot, SlotResult}

import java.time.LocalDate
import java.util.UUID
import scala.annotation.tailrec

/** The inputs of [[SubscriptionReconciler.reconcile]]. */
object ReconcilerInput {
  case class Subscription(
      id: UUID,
      cadence: SubscriptionCadence,
      intervalDays: Option[Int],
      intervalMonths: Option[Int],
      merchantId: Option[UUID],
      terms: List[SubscriptionTerm]
  )

  case class ManualPayment(subscriptionId: UUID, payment: SubscriptionManualPayment)

  /** An expense or a debit bank statement row that may pay a cycle. `amount` is positive (money out). */
  case class Charge(
      source: SubscriptionChargeSource,
      id: UUID,
      date: LocalDate,
      amount: BigDecimal,
      merchantId: Option[UUID]
  )
}

/** Why reconciliation could not run. */
enum SubscriptionReconcileError {
  case NegativeTolerance(toleranceDays: Int)
  case UnknownSubscription(paymentId: UUID, subscriptionId: UUID)
  case InvalidSchedule(subscriptionId: UUID, error: SubscriptionScheduleError)

  def message: String = this match {
    case NegativeTolerance(days) => s"Tolerance days must not be negative: $days"
    case UnknownSubscription(paymentId, subscriptionId) =>
      s"Manual payment $paymentId references unknown subscription $subscriptionId"
    case InvalidSchedule(subscriptionId, error) => s"Subscription $subscriptionId: ${error.message}"
  }
}

/** Pure reconciliation of every subscription's expected charge dates (cycles) against the user's charges.
  *
  *   1. Manual payments are credited first: each goes to the cycle of its own subscription whose bucket [due_i - tol,
  *      end_i) contains the paid date, one payment per cycle, earliest payment first. end_i is due_{i+1} - tol when the
  *      next cycle belongs to the same contiguous run of terms. For the last cycle of a run it is the end of the run
  *      (but never before due_i + tol + 1), so a payment made during a cancel/resume gap credits no cycle. 2. Every
  *      other cycle of every subscription is matched together by [[DateAmountMatcher]], so a charge is used at most
  *      once and same-amount collisions resolve across subscriptions. 3. A cycle with one charge is Matched, with
  *      several is Retried, and with none is Missing once its window has closed (dueDate + tol before today) or
  *      Upcoming otherwise.
  */
object SubscriptionReconciler {

  private case class CycleKey(subscriptionId: UUID, dueDate: LocalDate)

  private type Schedule = (ReconcilerInput.Subscription, List[SubscriptionSchedule.DueDate])
  private type BankResult = SlotResult[CycleKey, ReconcilerInput.Charge]

  private given Ordering[CycleKey] =
    Ordering.by[CycleKey, (Long, String)](k => (k.dueDate.toEpochDay, k.subscriptionId.toString))

  private given Ordering[ReconcilerInput.Charge] =
    Ordering.by[ReconcilerInput.Charge, (String, String)](c => (c.id.toString, c.source.entryName))

  def reconcile(
      subscriptions: List[ReconcilerInput.Subscription],
      manualPayments: List[ReconcilerInput.ManualPayment],
      candidates: List[ReconcilerInput.Charge],
      today: LocalDate,
      toleranceDays: Int
  ): Either[SubscriptionReconcileError, List[GetSubscriptionReconciliation.Response.Subscription]] =
    for {
      _ <- validate(subscriptions = subscriptions, manualPayments = manualPayments, toleranceDays = toleranceDays)
      schedules <- expandSchedules(subscriptions = subscriptions, today = today)
    } yield {
      val paymentsBySubscription = manualPayments.groupMap(_.subscriptionId)(_.payment)
      val manualMatches = creditManualPayments(schedules, paymentsBySubscription, toleranceDays)
      val slots = buildSlots(schedules, manualMatches)
      val bankResults = matchCharges(slots = slots, candidates = candidates, toleranceDays = toleranceDays)
      schedules.map { case (subscription, dates) =>
        GetSubscriptionReconciliation.Response.Subscription(
          subscriptionId = subscription.id,
          cycles = dates.sortBy(_.date.toEpochDay).map { due =>
            val key = CycleKey(subscription.id, due.date)
            toCycle(
              due = due,
              manualMatch = manualMatches.get(key),
              bankResult = bankResults.get(key),
              today = today,
              toleranceDays = toleranceDays
            )
          },
          manualPayments = paymentsBySubscription
            .getOrElse(subscription.id, List.empty)
            .sortBy(p => (p.paidDate.toEpochDay, p.id.toString))
        )
      }
    }

  private def validate(
      subscriptions: List[ReconcilerInput.Subscription],
      manualPayments: List[ReconcilerInput.ManualPayment],
      toleranceDays: Int
  ): Either[SubscriptionReconcileError, Unit] = {
    val knownIds = subscriptions.map(_.id).toSet
    for {
      _ <- Either.cond(toleranceDays >= 0, (), SubscriptionReconcileError.NegativeTolerance(toleranceDays))
      _ <- manualPayments
        .find(p => !knownIds.contains(p.subscriptionId))
        .map(p =>
          SubscriptionReconcileError.UnknownSubscription(paymentId = p.payment.id, subscriptionId = p.subscriptionId)
        )
        .toLeft(())
    } yield ()
  }

  private def expandSchedules(
      subscriptions: List[ReconcilerInput.Subscription],
      today: LocalDate
  ): Either[SubscriptionReconcileError, List[Schedule]] = {
    val schedules = subscriptions.map { subscription =>
      SubscriptionSchedule
        .dueDates(
          terms = subscription.terms,
          cadence = subscription.cadence,
          intervalDays = subscription.intervalDays,
          intervalMonths = subscription.intervalMonths,
          today = today
        )
        .map(subscription -> _)
        .left
        .map(SubscriptionReconcileError.InvalidSchedule(subscription.id, _))
    }
    schedules.collectFirst { case Left(error) => error }.toLeft(schedules.collect { case Right(s) => s })
  }

  private def creditManualPayments(
      schedules: List[Schedule],
      paymentsBySubscription: Map[UUID, List[SubscriptionManualPayment]],
      toleranceDays: Int
  ): Map[CycleKey, SubscriptionManualPayment] =
    schedules.flatMap { case (subscription, dates) =>
      assignManualPayments(
        terms = subscription.terms,
        dueDates = dates,
        payments = paymentsBySubscription.getOrElse(subscription.id, List.empty),
        toleranceDays = toleranceDays
      ).map { case (dueDate, payment) => CycleKey(subscription.id, dueDate) -> payment }
    }.toMap

  private def buildSlots(
      schedules: List[Schedule],
      manualMatches: Map[CycleKey, SubscriptionManualPayment]
  ): List[Slot[CycleKey]] =
    for {
      (subscription, dates) <- schedules
      due <- dates
      key = CycleKey(subscription.id, due.date)
      if !manualMatches.contains(key)
    } yield Slot(
      id = key,
      targetDate = due.date,
      amountRange = for {
        min <- due.term.amountMin
        max <- due.term.amountMax
      } yield (min, max),
      merchantHint = subscription.merchantId
    )

  private def matchCharges(
      slots: List[Slot[CycleKey]],
      candidates: List[ReconcilerInput.Charge],
      toleranceDays: Int
  ): Map[CycleKey, BankResult] =
    DateAmountMatcher
      .assign(
        slots,
        candidates.map(c => Candidate(c, c.date, c.amount, c.merchantId)),
        toleranceDays,
        (slot: Slot[CycleKey]) => slot.id.subscriptionId
      )
      .map(r => r.slot.id -> r)
      .toMap

  private def toCycle(
      due: SubscriptionSchedule.DueDate,
      manualMatch: Option[SubscriptionManualPayment],
      bankResult: Option[BankResult],
      today: LocalDate,
      toleranceDays: Int
  ): SubscriptionCycle =
    SubscriptionCycle(
      dueDate = due.date,
      termId = due.term.id,
      amountMin = due.term.amountMin,
      amountMax = due.term.amountMax,
      status = manualMatch match {
        case Some(payment) => manualStatus(payment = payment, dueDate = due.date)
        case None =>
          cycleStatus(dueDate = due.date, result = bankResult, today = today, toleranceDays = toleranceDays)
      }
    )

  private def manualStatus(payment: SubscriptionManualPayment, dueDate: LocalDate): SubscriptionCycleStatus =
    SubscriptionCycleStatus.Matched(
      charge = SubscriptionCharge(
        source = SubscriptionChargeSource.ManualPayment,
        id = payment.id,
        date = payment.paidDate,
        amount = payment.amount
      ),
      late = payment.paidDate.isAfter(dueDate)
    )

  /** Due date -> the manual payment credited to that cycle. See the bucket rules in the object comment. Payments are
    * taken earliest first and a cycle keeps only the first one in its bucket.
    */
  private def assignManualPayments(
      terms: List[SubscriptionTerm],
      dueDates: List[SubscriptionSchedule.DueDate],
      payments: List[SubscriptionManualPayment],
      toleranceDays: Int
  ): Map[LocalDate, SubscriptionManualPayment] = {
    val sorted = dueDates.sortBy(_.date.toEpochDay).toVector
    val anchors = sorted.map(d => SubscriptionSchedule.cadenceAnchor(terms, d.term))
    val buckets = sorted.indices.map { i =>
      val start = sorted(i).date.toEpochDay - toleranceDays
      val end =
        if (i + 1 < sorted.size && anchors(i + 1) == anchors(i)) Some(sorted(i + 1).date.toEpochDay - toleranceDays)
        else
          runEnd(terms, sorted(i).term).map(e => math.max(e.toEpochDay, sorted(i).date.toEpochDay + toleranceDays + 1))
      (start, end)
    }
    payments
      .sortBy(p => (p.paidDate.toEpochDay, p.id.toString))
      .foldLeft(Map.empty[LocalDate, SubscriptionManualPayment]) { (assigned, payment) =>
        val paidDay = payment.paidDate.toEpochDay
        val bucket = buckets.lastIndexWhere(_._1 <= paidDay)
        val dueDate = Option.when(bucket >= 0 && buckets(bucket)._2.forall(paidDay < _))(sorted(bucket).date)
        dueDate.filterNot(assigned.contains).fold(assigned)(d => assigned + (d -> payment))
      }
  }

  /** The end of the contiguous run of terms containing `term`, None when the run is open. */
  @tailrec
  private def runEnd(terms: List[SubscriptionTerm], term: SubscriptionTerm): Option[LocalDate] =
    term.effectiveTo match {
      case None => None
      case Some(end) =>
        terms.find(_.effectiveFrom == end) match {
          case Some(next) => runEnd(terms, next)
          case None => Some(end)
        }
    }

  private def cycleStatus(
      dueDate: LocalDate,
      result: Option[BankResult],
      today: LocalDate,
      toleranceDays: Int
  ): SubscriptionCycleStatus =
    result.flatMap(r => r.primary.map(_ -> r.extraAttempts)) match {
      case Some((primary, Nil)) =>
        SubscriptionCycleStatus.Matched(charge = toCharge(primary.id), late = primary.date.isAfter(dueDate))
      case Some((primary, extras)) =>
        SubscriptionCycleStatus.Retried(
          (primary :: extras).sortBy(c => (c.date.toEpochDay, c.id.id.toString)).map(c => toCharge(c.id))
        )
      case None if dueDate.plusDays(toleranceDays.toLong).isBefore(today) => SubscriptionCycleStatus.Missing
      case None => SubscriptionCycleStatus.Upcoming
    }

  private def toCharge(charge: ReconcilerInput.Charge): SubscriptionCharge =
    SubscriptionCharge(source = charge.source, id = charge.id, date = charge.date, amount = charge.amount)
}
