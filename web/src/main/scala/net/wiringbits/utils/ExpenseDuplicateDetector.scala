package net.wiringbits.utils

import net.wiringbits.api.models.expenses.GetExpenses

import java.util.UUID

/** Finds expenses that likely duplicate each other: one created from a bank statement row (the survivor) and one
  * entered by hand with a receipt (the loser), which the merge flow folds into the survivor.
  *
  * A pair qualifies when:
  *   - the survivor has a linked row and the loser has none; pairs where both or neither have a row never match.
  *   - the loser has a receipt and the survivor has none, mirroring the merge guards (the loser's receipt moves to the
  *     survivor, which can hold only one).
  *   - the amounts are exactly equal (BigDecimal value equality, no abs, no tolerance).
  *   - the dates are at most `ReconciliationMatcher.MaxDateDifferenceDays` apart in either direction, as in
  *     `ReconciliationMatcher.reconcile`. `GetExpenses.Response.Expense.date` is a non-optional `LocalDate`, so a
  *     missing date cannot be represented; that is the guarantee that a missing date never matches.
  *
  * A pair is `Strong` when both merchants are defined and equal, otherwise `Weak` (including when both are `None`).
  * Every qualifying pair is returned, so one expense can appear in several candidates (e.g. recurring same-amount
  * purchases at one merchant). Such candidates are flagged `ambiguous`: the tier alone does not identify the right
  * partner, so they need review rather than a one-click merge. A merge deletes the loser and gives the survivor a
  * receipt, so callers must drop every other candidate that shares either expense id after a merge or dismiss.
  *
  * Results are ordered by survivor date descending, then survivor id, then loser id.
  */
object ExpenseDuplicateDetector {

  enum Tier {
    case Strong, Weak
  }

  /** `receiptId` is the loser's receipt, the one the merge moves to the survivor. `ambiguous` is true when the survivor
    * or the loser also appears in another candidate.
    */
  case class Candidate(
      survivor: GetExpenses.Response.Expense,
      loser: GetExpenses.Response.Expense,
      receiptId: UUID,
      tier: Tier,
      ambiguous: Boolean
  )

  def detect(expenses: List[GetExpenses.Response.Expense]): List[Candidate] = {
    val survivors = expenses.filter(e => e.rowIds.nonEmpty && e.receiptIds.isEmpty)
    val losersByAmount = expenses
      .filter(_.rowIds.isEmpty)
      .flatMap(e => e.receiptIds.headOption.map(e -> _))
      .groupBy(_._1.amount)

    val pairs = survivors
      .flatMap { survivor =>
        losersByAmount
          .getOrElse(survivor.amount, Nil)
          .filter((loser, _) =>
            Math.abs(survivor.date.toEpochDay - loser.date.toEpochDay) <= ReconciliationMatcher.MaxDateDifferenceDays
          )
          .map((loser, receiptId) => (survivor, loser, receiptId))
      }
    val survivorCounts = pairs.groupBy(_._1.id).view.mapValues(_.size).toMap
    val loserCounts = pairs.groupBy(_._2.id).view.mapValues(_.size).toMap

    pairs
      .map { (survivor, loser, receiptId) =>
        val ambiguous = survivorCounts(survivor.id) > 1 || loserCounts(loser.id) > 1
        Candidate(survivor, loser, receiptId, tierFor(survivor, loser), ambiguous)
      }
      .sortBy(c => (-c.survivor.date.toEpochDay, c.survivor.id.toString, c.loser.id.toString))
  }

  private def tierFor(survivor: GetExpenses.Response.Expense, loser: GetExpenses.Response.Expense): Tier =
    if (survivor.merchantId.isDefined && survivor.merchantId == loser.merchantId) Tier.Strong else Tier.Weak
}
