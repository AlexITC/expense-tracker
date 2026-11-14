package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.components.pages.bankstatementreview.BankStatementReviewLogic

import java.time.LocalDate
import java.util.UUID

object ReconciliationMatcher {

  // Accounts for the gap between purchase date and bank settlement/posting date. `reconcile` applies it in both
  // directions; `matchRow` only allows the row to post after the expense.
  val MaxDateDifferenceDays = 3L

  // Tolerance for small amount differences (tips, rounding, FX adjustments) when no exact match exists.
  private val NearAmountTolerance = BigDecimal("1.00")

  sealed trait ProposedAction
  case class AutoLink(expenseId: UUID) extends ProposedAction
  case class NeedsReview(candidates: List[UUID]) extends ProposedAction
  case object CreateNew extends ProposedAction

  enum ReceiptMatch {
    case NoMatch
    case Single(receiptId: UUID)
    case Ambiguous(receiptIds: List[UUID])
  }

  def qualifyingCreateRequests(
      rows: List[GetBankStatement.StatementRowData],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): List[(GetBankStatement.StatementRowData, CreateExpenseFromRow.Request)] = {
    val effective = ImportRuleMatcher.effectiveRules(rules, merchants.map(m => m.id -> m.name))
    rows.flatMap { row =>
      val verdict = RowRuleVerdict.verdict(effective, row.description, row.amount)
      val merchantId = RowRuleVerdict.merchantId(verdict)
      val catIdOpt = merchantId
        .flatMap(mid => merchants.find(_.id == mid))
        .flatMap(m => RowRuleVerdict.categoryFor(verdict, m.categoryId))
      catIdOpt.map { catId =>
        val req = CreateExpenseFromRow.Request(
          amount = row.amount.abs,
          date = row.date,
          time = None,
          merchantId = merchantId,
          notes = BankStatementReviewLogic.rowNotes(verdict, row),
          splits = List(CreateExpenseFromRow.Split(catId, row.amount.abs)),
          receiptId = None
        )
        row -> req
      }
    }
  }

  /** Auto-reconcile's matching. It intentionally keeps a symmetric ±3-day window and exact amounts only, so it can
    * propose different links than `matchRow`, which the review page uses. Unlinked, in-window, exact-amount expenses
    * with an attached receipt are tried first and win regardless of the row's rules, since the receipt already vouches
    * for the purchase. Only when there are none does the rule's merchant decide the candidates. Rows are processed in
    * order and an expense auto-linked to an earlier row is no longer a candidate for later rows, so one run never
    * proposes the same expense for two rows.
    */
  def reconcile(
      rows: List[GetBankStatement.StatementRowData],
      expenses: List[GetExpenses.Response.Expense],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): Map[UUID, ProposedAction] = {
    val effective = ImportRuleMatcher.effectiveRules(rules, merchants.map(m => m.id -> m.name))
    val unlinkedExpenses = expenses.filter(_.rowIds.isEmpty)

    rows
      .filter(row => row.expenseId.isEmpty && row.ignoredAt.isEmpty)
      .foldLeft((Map.empty[UUID, ProposedAction], Set.empty[UUID])) { case ((acc, claimed), row) =>
        val available = unlinkedExpenses.filterNot(e => claimed.contains(e.id))
        val receiptBacked = available.filter { e =>
          e.receiptIds.nonEmpty && row.amount.abs == e.amount.abs &&
          Math.abs(row.date.toEpochDay - e.date.toEpochDay) <= MaxDateDifferenceDays
        }
        val action =
          if (receiptBacked.nonEmpty) actionFor(receiptBacked)
          else
            RowRuleVerdict.ruleMerchantId(effective, row.description, row.amount) match {
              case Some(ruleMerchantId) =>
                val candidates = available.filter { expense =>
                  val amountMatch = row.amount.abs == expense.amount.abs
                  val merchantMatch = expense.merchantId.contains(ruleMerchantId)
                  val dateMatch = Math.abs(row.date.toEpochDay - expense.date.toEpochDay) <= MaxDateDifferenceDays
                  amountMatch && merchantMatch && dateMatch
                }
                actionFor(candidates)
              case None => CreateNew
            }
        val nowClaimed = action match {
          case AutoLink(expenseId) => claimed + expenseId
          case _ => claimed
        }
        (acc.updated(row.id, action), nowClaimed)
      }
      ._1
  }

  /** Proposes how to resolve a single row against the user's expenses. An expense qualifies when it is unlinked, its
    * date is between 0 and 3 days before the row's date, and it agrees with the row's rule verdict (same merchant for
    * SetMerchant, a split in the category for SetCategory). An unlinked, in-window expense with an attached receipt
    * qualifies even when it disagrees with the verdict, since the receipt already vouches for the purchase.
    * Exact-amount candidates win; otherwise candidates within ±1.00 are used. Within the chosen tier, receipt-backed
    * candidates win over the rest; tiers are never compared against each other.
    */
  def matchRow(
      row: GetBankStatement.StatementRowData,
      verdict: RowRuleVerdict.Verdict,
      expenses: List[GetExpenses.Response.Expense]
  ): ProposedAction = {
    val constraint: GetExpenses.Response.Expense => Boolean =
      RowRuleVerdict.merchantId(verdict) match {
        case Some(merchantId) => _.merchantId.contains(merchantId)
        case None =>
          RowRuleVerdict.categoryId(verdict) match {
            case Some(categoryId) => _.splits.exists(_.categoryId == categoryId)
            case None => _ => true
          }
      }
    val eligible = expenses.filter(e => isMatchRowCandidate(row, e) && (constraint(e) || e.receiptIds.nonEmpty))
    val exact = eligible.filter(_.amount.abs == row.amount.abs)
    val tier =
      if (exact.nonEmpty) exact
      else eligible.filter(e => (row.amount.abs - e.amount.abs).abs <= NearAmountTolerance)
    val receiptBacked = tier.filter(_.receiptIds.nonEmpty)
    actionFor(if (receiptBacked.nonEmpty) receiptBacked else tier)
  }

  /** Finds the receipt for a row. A receipt qualifies when its amount equals the row's absolute amount exactly and its
    * date is between 0 and `MaxDateDifferenceDays` days before the row's date (the same window as `matchRow`). Receipts
    * without an amount or a date never qualify, and the merchant is not considered. More than one candidate is
    * `Ambiguous`.
    */
  def matchReceipt(
      row: GetBankStatement.StatementRowData,
      receipts: List[GetReceipts.Response.Receipt]
  ): ReceiptMatch = {
    val candidates = receipts.filter { r =>
      r.amount.contains(row.amount.abs) && r.date.exists(d => rowPostsWithinWindow(row, d))
    }
    candidates match {
      case List(single) => ReceiptMatch.Single(single.id)
      case Nil => ReceiptMatch.NoMatch
      case multiple => ReceiptMatch.Ambiguous(multiple.map(_.id))
    }
  }

  /** Unlinked expenses grouped by date, built once so each row only looks at the dates it can match. */
  def unlinkedByDate(
      expenses: List[GetExpenses.Response.Expense]
  ): Map[LocalDate, List[GetExpenses.Response.Expense]] =
    expenses.filter(_.rowIds.isEmpty).groupBy(_.date)

  /** An index-based prefilter for `matchRow`: the expenses on the dates its window can reach. `matchRow` re-checks
    * every candidate with `isMatchRowCandidate`, so the window and the unlinked rule are only defined there.
    */
  def candidatesFor(
      row: GetBankStatement.StatementRowData,
      byDate: Map[LocalDate, List[GetExpenses.Response.Expense]]
  ): List[GetExpenses.Response.Expense] =
    (0L to MaxDateDifferenceDays).toList
      .flatMap(d => byDate.getOrElse(row.date.minusDays(d), Nil))
      .filter(isMatchRowCandidate(row, _))

  // `matchRow`'s window and unlinked rule: the expense is unlinked and within `rowPostsWithinWindow`.
  private def isMatchRowCandidate(
      row: GetBankStatement.StatementRowData,
      e: GetExpenses.Response.Expense
  ): Boolean =
    e.rowIds.isEmpty && rowPostsWithinWindow(row, e.date)

  // The row posts 0 to `MaxDateDifferenceDays` days after `date`.
  private def rowPostsWithinWindow(row: GetBankStatement.StatementRowData, date: LocalDate): Boolean =
    !row.date.isBefore(date) && !row.date.isAfter(date.plusDays(MaxDateDifferenceDays))

  private def actionFor(candidates: List[GetExpenses.Response.Expense]): ProposedAction =
    candidates match {
      case List(single) => AutoLink(single.id)
      case Nil => CreateNew
      case multiple => NeedsReview(multiple.map(_.id))
    }
}
