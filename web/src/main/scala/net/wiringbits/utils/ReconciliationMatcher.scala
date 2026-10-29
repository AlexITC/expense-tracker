package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.GetMerchants
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}

import java.util.UUID

object ReconciliationMatcher {

  // Accounts for the gap between purchase date and bank settlement/posting date.
  private val MaxDateDifferenceDays = 3L

  // Tolerance for small amount differences (tips, rounding, FX adjustments) when no exact match exists.
  private val NearAmountTolerance = BigDecimal("1.00")

  sealed trait ProposedAction
  case class AutoLink(expenseId: UUID) extends ProposedAction
  case class NeedsReview(candidates: List[UUID]) extends ProposedAction
  case object CreateNew extends ProposedAction

  def qualifyingCreateRequests(
      rows: List[GetBankStatement.StatementRowData],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): List[(GetBankStatement.StatementRowData, CreateExpenseFromRow.Request)] = {
    val effective = ImportRuleMatcher.effectiveRules(rules, merchants.map(m => m.id -> m.name))
    rows.flatMap { row =>
      val merchantId = RowRuleVerdict.ruleMerchantId(effective, row.description)
      val catIdOpt = merchantId.flatMap(mid => merchants.find(_.id == mid).flatMap(_.categoryId))
      catIdOpt.map { catId =>
        val req = CreateExpenseFromRow.Request(
          amount = row.amount.abs,
          date = row.date,
          time = None,
          merchantId = merchantId,
          notes = Some(row.description).filter(_.nonEmpty),
          splits = List(CreateExpenseFromRow.Split(catId, row.amount.abs)),
          receiptId = None
        )
        row -> req
      }
    }
  }

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
      .map { row =>
        val action = RowRuleVerdict.ruleMerchantId(effective, row.description) match {
          case Some(ruleMerchantId) =>
            val candidates = unlinkedExpenses.filter { expense =>
              val amountMatch = row.amount.abs == expense.amount.abs
              val merchantMatch = expense.merchantId.contains(ruleMerchantId)
              val dateMatch = Math.abs(row.date.toEpochDay - expense.date.toEpochDay) <= MaxDateDifferenceDays
              amountMatch && merchantMatch && dateMatch
            }
            actionFor(candidates)
          case None => CreateNew
        }
        row.id -> action
      }
      .toMap
  }

  /** Proposes how to resolve a single row against the user's expenses. An expense qualifies when it is unlinked, its
    * date is between 0 and 3 days before the row's date, and it agrees with the row's rule verdict (same merchant for
    * SetMerchant, a split in the category for SetCategory). Exact-amount candidates win; otherwise candidates within
    * ±1.00 are used.
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
    val eligible = expenses.filter { e =>
      e.rowIds.isEmpty &&
      !row.date.isBefore(e.date) &&
      !row.date.isAfter(e.date.plusDays(MaxDateDifferenceDays)) &&
      constraint(e)
    }
    val exact = eligible.filter(_.amount.abs == row.amount.abs)
    val candidates =
      if (exact.nonEmpty) exact
      else eligible.filter(e => (row.amount.abs - e.amount.abs).abs <= NearAmountTolerance)
    actionFor(candidates)
  }

  private def actionFor(candidates: List[GetExpenses.Response.Expense]): ProposedAction =
    candidates match {
      case List(single) => AutoLink(single.id)
      case Nil => CreateNew
      case multiple => NeedsReview(multiple.map(_.id))
    }
}
