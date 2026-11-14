package net.wiringbits.utils

import net.wiringbits.api.models.expenses.{GetExpenses, MergeShape}

import java.util.UUID

/** Finds expenses that likely duplicate each other: one created from a bank statement row (the survivor) and one
  * entered by hand without a row (the loser), which the merge flow folds into the survivor. Three shapes qualify:
  *   - receipt shape: the survivor has a row and no receipt, the loser has no row and a receipt. The merge moves the
  *     loser's receipt to the survivor, which can hold only one. The candidate's shape is
  *     `Shape.Receipt(loserReceiptId)`.
  *   - orphan shape: the survivor has a row (with or without a receipt), the loser has neither a row nor a receipt. The
  *     merge just deletes the loser (reassigning its refunds). The candidate's shape is `Shape.Orphan`.
  *   - receipt-on-both shape: the survivor has a row and a receipt, the loser has no row and a receipt (likely a second
  *     copy of the same ticket). The merge keeps the survivor's receipt and deletes the loser with its receipt. The
  *     candidate's shape is `Shape.ReceiptOnBoth(survivorReceiptId, loserReceiptId)`.
  *
  * In every shape:
  *   - the amounts are exactly equal (BigDecimal value equality, no abs, no tolerance).
  *   - the dates are at most `ReconciliationMatcher.MaxDateDifferenceDays` apart in either direction, as in
  *     `ReconciliationMatcher.reconcile`. `GetExpenses.Response.Expense.date` is a non-optional `LocalDate`, so a
  *     missing date cannot be represented; that is the guarantee that a missing date never matches.
  *
  * A pair is `Strong` when both merchants are defined and equal, otherwise `Weak` (including when both are `None`).
  * Every qualifying pair of any shape is returned, so one expense can appear in several candidates (e.g. recurring
  * same-amount purchases at one merchant). Such candidates are flagged `ambiguous` (counted across all shapes): the
  * tier alone does not identify the right partner, so they need review rather than a one-click merge. A merge deletes
  * the loser and may give the survivor a receipt, so callers must drop every other candidate that shares either expense
  * id after a merge or dismiss.
  *
  * Results are ordered by survivor date descending, then survivor id, then loser id.
  */
object ExpenseDuplicateDetector {

  enum Tier {
    case Strong, Weak
  }

  /** Which kind of pair a candidate is, deciding what the merge does with the loser. */
  enum Shape {

    /** The loser has a receipt, `loserReceiptId`, which the merge moves to the survivor. */
    case Receipt(loserReceiptId: UUID)

    /** The loser has no receipt; the merge deletes it. */
    case Orphan

    /** Both have a receipt; the merge keeps the survivor's and deletes the loser with its receipt. */
    case ReceiptOnBoth(survivorReceiptId: UUID, loserReceiptId: UUID)
  }

  /** The wire shape the server expects for merging a candidate of shape `s`. */
  def mergeShape(s: Shape): MergeShape = s match {
    case Shape.Receipt(_) => MergeShape.Receipt
    case Shape.Orphan => MergeShape.Orphan
    case Shape.ReceiptOnBoth(_, _) => MergeShape.ReceiptOnBoth
  }

  /** `ambiguous` is true when the survivor or the loser also appears in another candidate. */
  case class Candidate(
      survivor: GetExpenses.Response.Expense,
      loser: GetExpenses.Response.Expense,
      shape: Shape,
      tier: Tier,
      ambiguous: Boolean
  )

  def detect(expenses: List[GetExpenses.Response.Expense]): List[Candidate] = {
    def withinWindow(survivor: GetExpenses.Response.Expense, loser: GetExpenses.Response.Expense): Boolean =
      Math.abs(survivor.date.toEpochDay - loser.date.toEpochDay) <= ReconciliationMatcher.MaxDateDifferenceDays

    def pairsFor(
        survivors: List[GetExpenses.Response.Expense],
        losers: List[(GetExpenses.Response.Expense, Shape)]
    ): List[(GetExpenses.Response.Expense, GetExpenses.Response.Expense, Shape)] = {
      val losersByAmount = losers.groupBy(_._1.amount)
      survivors.flatMap { survivor =>
        losersByAmount
          .getOrElse(survivor.amount, Nil)
          .filter((loser, _) => withinWindow(survivor = survivor, loser = loser))
          .map((loser, shape) => (survivor, loser, shape))
      }
    }

    val withRow = expenses.filter(_.rowIds.nonEmpty)
    val withoutRow = expenses.filter(_.rowIds.isEmpty)
    val receiptPairs = pairsFor(
      survivors = withRow.filter(_.receiptIds.isEmpty),
      losers = withoutRow.flatMap(e => e.receiptIds.headOption.map(r => e -> Shape.Receipt(r)))
    )
    val orphanPairs = pairsFor(
      survivors = withRow,
      losers = withoutRow.filter(_.receiptIds.isEmpty).map(_ -> Shape.Orphan)
    )
    val receiptOnBothPairs = withRow.flatMap { survivor =>
      survivor.receiptIds.headOption.toList.flatMap { survivorReceiptId =>
        pairsFor(
          survivors = List(survivor),
          losers = withoutRow.flatMap(e =>
            e.receiptIds.headOption.map(r =>
              e -> Shape.ReceiptOnBoth(survivorReceiptId = survivorReceiptId, loserReceiptId = r)
            )
          )
        )
      }
    }
    val pairs = receiptPairs ++ orphanPairs ++ receiptOnBothPairs
    val survivorCounts = pairs.groupBy(_._1.id).view.mapValues(_.size).toMap
    val loserCounts = pairs.groupBy(_._2.id).view.mapValues(_.size).toMap

    pairs
      .map { (survivor, loser, shape) =>
        val ambiguous = survivorCounts(survivor.id) > 1 || loserCounts(loser.id) > 1
        Candidate(survivor = survivor, loser = loser, shape, tierFor(survivor = survivor, loser = loser), ambiguous)
      }
      .sortBy(c => (-c.survivor.date.toEpochDay, c.survivor.id.toString, c.loser.id.toString))
  }

  /** Whether `c` can be merged in bulk without review: a Strong, unambiguous pair. */
  def isBulkEligible(c: Candidate): Boolean = c.tier == Tier.Strong && !c.ambiguous

  private def tierFor(survivor: GetExpenses.Response.Expense, loser: GetExpenses.Response.Expense): Tier =
    if (survivor.merchantId.isDefined && survivor.merchantId == loser.merchantId) Tier.Strong else Tier.Weak
}
