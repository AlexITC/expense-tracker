package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher

import java.util.UUID

object ReconciliationMatcher {

  // Accounts for the gap between purchase date and bank settlement/posting date.
  private val MaxDateDifferenceDays = 3L

  sealed trait ProposedAction
  case class AutoLink(expenseId: UUID) extends ProposedAction
  case class NeedsReview(candidates: List[UUID]) extends ProposedAction
  case object CreateNew extends ProposedAction

  def qualifyingCreateRequests(
      rows: List[GetBankStatement.StatementRowData],
      rules: List[ImportRuleMatcher.Rule],
      merchants: List[GetMerchants.Response.Merchant]
  ): List[(GetBankStatement.StatementRowData, CreateExpenseFromRow.Request)] =
    rows.flatMap { row =>
      ImportRuleMatcher.findRowWinner(rules, row.description) match {
        case Some(w) if w.action == ImportRuleAction.SetMerchant =>
          val merchantId = w.merchantId
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
        case _ => None
      }
    }

  def reconcile(
      rows: List[GetBankStatement.StatementRowData],
      expenses: List[GetExpenses.Response.Expense],
      rules: List[ImportRuleMatcher.Rule]
  ): Map[UUID, ProposedAction] = {
    val unlinkedExpenses = expenses.filter(_.rowIds.isEmpty)

    rows
      .filter(row => row.expenseId.isEmpty && row.ignoredAt.isEmpty)
      .map { row =>
        val action = ImportRuleMatcher.findRowWinner(rules, row.description) match {
          case Some(rule) if rule.action == ImportRuleAction.SetMerchant =>
            // rule.pattern is used as the merchant name proxy: merchantId is not available
            // here without a merchant lookup, so the pattern (which identifies the merchant)
            // is the best signal we have.
            val normalizedRulePattern = ImportRuleMatcher.normalize(rule.pattern)
            val candidates = unlinkedExpenses.filter { expense =>
              val amountMatch = row.amount.abs == expense.amount.abs
              val merchantMatch =
                expense.merchantName.exists(n => ImportRuleMatcher.normalize(n) == normalizedRulePattern)
              val dateMatch = Math.abs(row.date.toEpochDay - expense.date.toEpochDay) <= MaxDateDifferenceDays
              amountMatch && merchantMatch && dateMatch
            }
            candidates match {
              case List(single) => AutoLink(single.id)
              case Nil => CreateNew
              case multiple => NeedsReview(multiple.map(_.id))
            }
          case _ => CreateNew
        }
        row.id -> action
      }
      .toMap
  }
}
