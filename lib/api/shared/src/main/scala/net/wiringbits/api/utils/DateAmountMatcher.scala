package net.wiringbits.api.utils

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.annotation.tailrec

/** Pure matching of expected payments (slots) to actual charges (candidates) by date, amount and merchant.
  *
  * A pair is eligible when the candidate date is within `toleranceDays` of the slot target date (inclusive on both
  * sides), the amount is inside the slot's inclusive range when there is one, and the merchants are compatible (when
  * both are set they must be equal). A slot without an amount range only accepts candidates whose merchant equals its
  * hint, so a slot with neither a range nor a hint accepts nothing: there is no silent wildcard.
  *
  * Assignment runs in two passes:
  *   1. Greedy one-to-one primary matching over all eligible pairs, preferring a merchant equal to the hint, then the
  *      smaller day difference, then the earlier slot date, then the slot and candidate ids. 2. Every still-unused
  *      eligible candidate becomes an extra attempt of the eligible matched slot with the nearest target date. A
  *      candidate eligible for slots of more than one group is ambiguous and is not credited anywhere, so a retry is
  *      never credited across groups.
  */
object DateAmountMatcher {

  case class Slot[S](
      id: S,
      targetDate: LocalDate,
      amountRange: Option[(BigDecimal, BigDecimal)],
      merchantHint: Option[UUID]
  )

  case class Candidate[C](id: C, date: LocalDate, amount: BigDecimal, merchantId: Option[UUID])

  /** The outcome for one slot, extra attempts are ordered by date then id. */
  case class SlotResult[S, C](slot: Slot[S], primary: Option[Candidate[C]], extraAttempts: List[Candidate[C]])

  def isEligible[S, C](slot: Slot[S], candidate: Candidate[C], toleranceDays: Int): Boolean = {
    val withinWindow = dayDistance(slot, candidate) <= toleranceDays
    val merchantCompatible = (slot.merchantHint, candidate.merchantId) match {
      case (Some(hint), Some(merchant)) => hint == merchant
      case _ => true
    }
    val amountAccepted = slot.amountRange match {
      case Some((min, max)) => candidate.amount >= min && candidate.amount <= max
      case None => prefersMerchant(slot, candidate)
    }
    withinWindow && merchantCompatible && amountAccepted
  }

  def assign[S: Ordering, C: Ordering, G](
      slots: List[Slot[S]],
      candidates: List[Candidate[C]],
      toleranceDays: Int,
      group: Slot[S] => G
  ): List[SlotResult[S, C]] = {
    val slotVector = slots.toVector
    val candidateVector = candidates.toVector
    val pairs = eligiblePairs(slotVector, candidateVector, toleranceDays)
    val primaries = primaryMatches(pairs)
    val extras = extraAttempts(pairs, primaries, group)

    slotVector.zipWithIndex.map { case (slot, slotIndex) =>
      val attempts = extras
        .getOrElse(slotIndex, List.empty)
        .map(candidateVector)
        .sortBy(c => (c.date, c.id))(using Ordering.Tuple2(using localDateOrdering, Ordering[C]))
      SlotResult(slot, primaries.get(slotIndex).map(candidateVector), attempts)
    }.toList
  }

  private case class Pair[S, C](slotIndex: Int, slot: Slot[S], candidateIndex: Int, candidate: Candidate[C])

  /** Every eligible pair, only comparing each slot with the candidates inside its date window. */
  private def eligiblePairs[S, C](
      slots: Vector[Slot[S]],
      candidates: Vector[Candidate[C]],
      toleranceDays: Int
  ): Vector[Pair[S, C]] = {
    val byDate = candidates.indices.sortBy(i => candidates(i).date.toEpochDay).toVector
    val sortedDays = byDate.map(i => candidates(i).date.toEpochDay)
    for {
      (slot, slotIndex) <- slots.zipWithIndex
      windowStart = slot.targetDate.toEpochDay - toleranceDays
      windowEnd = slot.targetDate.toEpochDay + toleranceDays
      firstPosition = lowerBound(sortedDays, day = windowStart, from = 0, until = sortedDays.size)
      position <- (firstPosition until sortedDays.size).takeWhile(p => sortedDays(p) <= windowEnd)
      candidateIndex = byDate(position)
      candidate = candidates(candidateIndex)
      if isEligible(slot, candidate, toleranceDays)
    } yield Pair(slotIndex = slotIndex, slot = slot, candidateIndex = candidateIndex, candidate = candidate)
  }

  /** The first position in `sortedDays` holding a day on or after `day`. */
  @tailrec
  private def lowerBound(sortedDays: Vector[Long], day: Long, from: Int, until: Int): Int =
    if (from >= until) from
    else {
      val middle = (from + until) >>> 1
      if (sortedDays(middle) < day) lowerBound(sortedDays, day = day, from = middle + 1, until = until)
      else lowerBound(sortedDays, day = day, from = from, until = middle)
    }

  /** Merchant equal to the hint first, then the closest date, then the earlier slot, then the ids. */
  private def pairOrdering[S: Ordering, C: Ordering]: Ordering[Pair[S, C]] =
    Ordering.by[Pair[S, C], (Boolean, Long, LocalDate, S, C)] { p =>
      (
        !prefersMerchant(p.slot, p.candidate),
        dayDistance(p.slot, p.candidate),
        p.slot.targetDate,
        p.slot.id,
        p.candidate.id
      )
    }(using Ordering.Tuple5(using Ordering.Boolean, Ordering.Long, localDateOrdering, Ordering[S], Ordering[C]))

  /** Greedy one-to-one matching: slot index -> candidate index. */
  private def primaryMatches[S: Ordering, C: Ordering](pairs: Vector[Pair[S, C]]): Map[Int, Int] =
    pairs
      .sorted(using pairOrdering[S, C])
      .foldLeft((Map.empty[Int, Int], Set.empty[Int])) { case ((matched, used), p) =>
        if (matched.contains(p.slotIndex) || used.contains(p.candidateIndex)) (matched, used)
        else (matched + (p.slotIndex -> p.candidateIndex), used + p.candidateIndex)
      }
      ._1

  /** Credits each unused candidate to its nearest matched slot, unless it is eligible for slots of several groups.
    * Returns slot index -> candidate indexes.
    */
  private def extraAttempts[S: Ordering, C, G](
      pairs: Vector[Pair[S, C]],
      primaries: Map[Int, Int],
      group: Slot[S] => G
  ): Map[Int, List[Int]] = {
    val used = primaries.values.toSet
    val nearestOrdering: Ordering[Pair[S, C]] = Ordering.by[Pair[S, C], (Long, LocalDate, S)] { p =>
      (dayDistance(p.slot, p.candidate), p.slot.targetDate, p.slot.id)
    }(using Ordering.Tuple3(using Ordering.Long, localDateOrdering, Ordering[S]))
    pairs
      .filterNot(p => used.contains(p.candidateIndex))
      .groupBy(_.candidateIndex)
      .toList
      .flatMap { case (candidateIndex, candidatePairs) =>
        val singleGroup = candidatePairs.map(p => group(p.slot)).distinct.size == 1
        val nearestMatched =
          candidatePairs.filter(p => primaries.contains(p.slotIndex)).minOption(using nearestOrdering)
        nearestMatched.filter(_ => singleGroup).map(_.slotIndex -> candidateIndex)
      }
      .groupMap(_._1)(_._2)
  }

  private val localDateOrdering: Ordering[LocalDate] = Ordering.fromLessThan(_.isBefore(_))

  private def dayDistance[S, C](slot: Slot[S], candidate: Candidate[C]): Long =
    math.abs(ChronoUnit.DAYS.between(slot.targetDate, candidate.date))

  private def prefersMerchant[S, C](slot: Slot[S], candidate: Candidate[C]): Boolean =
    slot.merchantHint.isDefined && slot.merchantHint == candidate.merchantId
}
