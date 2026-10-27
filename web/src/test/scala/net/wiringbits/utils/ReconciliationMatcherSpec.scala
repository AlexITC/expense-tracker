package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.ImportRuleMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class ReconciliationMatcherSpec extends AnyWordSpec {

  private val merchantId = UUID.randomUUID()
  private val categoryId = UUID.randomUUID()
  private val baseDate = LocalDate.of(2024, 3, 15)

  private val merchant = GetMerchants.Response.Merchant(
    id = merchantId,
    name = "Amazon",
    notes = None,
    categoryId = Some(categoryId),
    reviewed = false,
    createdAt = Instant.now()
  )

  private val matchingRule = ImportRuleMatcher.Rule(
    id = UUID.randomUUID(),
    action = ImportRuleAction.SetMerchant,
    pattern = "Amazon",
    merchantId = Some(merchantId)
  )

  private def makeRow(
      amount: BigDecimal = BigDecimal("-100.00"),
      date: LocalDate = baseDate,
      description: String = "AMAZON PRIME PURCHASE"
  ): GetBankStatement.StatementRowData =
    GetBankStatement.StatementRowData(
      id = UUID.randomUUID(),
      index = 0,
      date = date,
      amount = amount,
      description = description,
      manuallyEdited = false,
      confirmedAt = None,
      expenseId = None,
      refundId = None,
      ignoredAt = None,
      txnType = "purchase",
      expenseMerchantId = None,
      expenseCategoryId = None
    )

  private def makeExpense(
      amount: BigDecimal = BigDecimal("100.00"),
      date: LocalDate = baseDate,
      mName: Option[String] = Some("Amazon"),
      rowIds: List[UUID] = Nil
  ): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = amount,
      date = date,
      time = None,
      merchantId = Some(merchantId),
      merchantName = mName,
      notes = None,
      splits = Nil,
      createdAt = Instant.now(),
      receiptIds = Nil,
      rowIds = rowIds
    )

  "ReconciliationMatcher.qualifyingCreateRequests" should {

    "build a request when the winner is SetMerchant and the merchant has a categoryId" in {
      val row = makeRow()
      val result = ReconciliationMatcher.qualifyingCreateRequests(List(row), List(matchingRule), List(merchant))
      result must have size 1
      val (_, req) = result.head
      req.amount must be(row.amount.abs)
      req.merchantId must be(matchingRule.merchantId)
      req.splits must be(List(CreateExpenseFromRow.Split(categoryId, row.amount.abs)))
      req.notes must be(Some(row.description))
    }

    "exclude a row when the matched merchant has no categoryId" in {
      val row = makeRow()
      val result = ReconciliationMatcher.qualifyingCreateRequests(
        List(row),
        List(matchingRule),
        List(merchant.copy(categoryId = None))
      )
      result must be(empty)
    }

    "exclude a row when the winning rule action is Ignore" in {
      val ignoreRule = matchingRule.copy(action = ImportRuleAction.Ignore)
      val row = makeRow()
      val result = ReconciliationMatcher.qualifyingCreateRequests(List(row), List(ignoreRule), List(merchant))
      result must be(empty)
    }

    "build a request from a merchant name when there are no explicit rules" in {
      val row = makeRow()
      val result = ReconciliationMatcher.qualifyingCreateRequests(List(row), Nil, List(merchant))
      result must have size 1
      val (_, req) = result.head
      req.merchantId must be(Some(merchantId))
      req.splits must be(List(CreateExpenseFromRow.Split(categoryId, row.amount.abs)))
    }

    "exclude a row when no rule matches the description" in {
      val row = makeRow(description = "UNRECOGNIZED VENDOR XYZ 99999")
      val result = ReconciliationMatcher.qualifyingCreateRequests(List(row), List(matchingRule), List(merchant))
      result must be(empty)
    }
  }

  "ReconciliationMatcher.reconcile" should {

    "produce AutoLink when exactly one expense matches all three criteria" in {
      val row = makeRow()
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "produce CreateNew when no expense matches" in {
      val row = makeRow()
      val expense = makeExpense(amount = BigDecimal("999.00"))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce NeedsReview when multiple expenses match" in {
      val row = makeRow()
      val expense1 = makeExpense()
      val expense2 = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense1, expense2), List(matchingRule), Nil)
      result(row.id) match {
        case ReconciliationMatcher.NeedsReview(ids) =>
          ids must contain theSameElementsAs List(expense1.id, expense2.id)
        case other => fail(s"Expected NeedsReview, got $other")
      }
    }

    "accept date difference of exactly +3 days" in {
      val row = makeRow(date = baseDate)
      val expense = makeExpense(date = baseDate.plusDays(3))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "accept date difference of exactly -3 days" in {
      val row = makeRow(date = baseDate)
      val expense = makeExpense(date = baseDate.minusDays(3))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "reject date difference of exactly +4 days" in {
      val row = makeRow(date = baseDate)
      val expense = makeExpense(date = baseDate.plusDays(4))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "reject date difference of exactly -4 days" in {
      val row = makeRow(date = baseDate)
      val expense = makeExpense(date = baseDate.minusDays(4))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce CreateNew when row description matches no rule" in {
      val row = makeRow(description = "UNRECOGNIZED VENDOR XYZ 99999")
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "skip rows that already have an expenseId" in {
      val row = makeRow().copy(expenseId = Some(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(makeExpense()), List(matchingRule), Nil)
      result.keys must not contain row.id
    }

    "skip rows that are ignored" in {
      val row = makeRow().copy(ignoredAt = Some(Instant.now()))
      val result = ReconciliationMatcher.reconcile(List(row), List(makeExpense()), List(matchingRule), Nil)
      result.keys must not contain row.id
    }

    "exclude expenses that already have rowIds from matching" in {
      val row = makeRow()
      val expense = makeExpense(rowIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce CreateNew when merchant name does not match the rule pattern" in {
      val row = makeRow()
      val expense = makeExpense(mName = Some("Walmart"))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce CreateNew when expense has no merchant name" in {
      val row = makeRow()
      val expense = makeExpense(mName = None)
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce AutoLink from a merchant name when there are no explicit rules" in {
      val row = makeRow()
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), Nil, List(merchant))
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "produce CreateNew when the winning rule action is Ignore" in {
      val ignoreRule = ImportRuleMatcher.Rule(
        id = UUID.randomUUID(),
        action = ImportRuleAction.Ignore,
        pattern = "Amazon",
        merchantId = None
      )
      val row = makeRow()
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(ignoreRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }
  }
}
