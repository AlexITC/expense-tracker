package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
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
    action = ImportRuleAction.SetMerchant(merchantId),
    pattern = "Amazon"
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
      mId: Option[UUID] = Some(merchantId),
      splits: List[GetExpenses.Response.Split] = Nil,
      rowIds: List[UUID] = Nil
  ): GetExpenses.Response.Expense =
    GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = amount,
      date = date,
      time = None,
      merchantId = mId,
      merchantName = Some("Amazon"),
      notes = None,
      splits = splits,
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
      req.merchantId must be(matchingRule.action.merchantIdOpt)
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

  "reconcile" should {

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

    "produce CreateNew when the expense merchantId differs from the rule's merchant" in {
      val row = makeRow()
      val expense = makeExpense(mId = Some(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce CreateNew when the expense merchantId is None" in {
      val row = makeRow()
      val expense = makeExpense(mId = None)
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
        pattern = "Amazon"
      )
      val row = makeRow()
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(ignoreRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }
  }

  "matchRow" should {

    def split(catId: UUID) = GetExpenses.Response.Split(UUID.randomUUID(), catId, BigDecimal("100.00"))

    "produce CreateNew when no expense qualifies" in {
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, Nil) must be(ReconciliationMatcher.CreateNew)
    }

    "produce AutoLink when exactly one expense qualifies, dated the same day as the row" in {
      val expense = makeExpense()
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(expense)) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "produce NeedsReview when several expenses match exactly" in {
      val e1 = makeExpense()
      val e2 = makeExpense()
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(e1, e2)) must be(
        ReconciliationMatcher.NeedsReview(List(e1.id, e2.id))
      )
    }

    "prefer an exact amount over a near one" in {
      val exact = makeExpense()
      val near = makeExpense(amount = BigDecimal("100.50"))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(near, exact)) must be(
        ReconciliationMatcher.AutoLink(exact.id)
      )
    }

    "produce AutoLink for a single expense within 1.00" in {
      val near = makeExpense(amount = BigDecimal("101.00"))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(near)) must be(
        ReconciliationMatcher.AutoLink(near.id)
      )
    }

    "produce NeedsReview when two expenses are within 1.00 and none is exact" in {
      val e1 = makeExpense(amount = BigDecimal("99.50"))
      val e2 = makeExpense(amount = BigDecimal("100.75"))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(e1, e2)) must be(
        ReconciliationMatcher.NeedsReview(List(e1.id, e2.id))
      )
    }

    "produce CreateNew when the amount differs by 1.01" in {
      val expense = makeExpense(amount = BigDecimal("101.01"))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(expense)) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "accept a row dated 3 days after the expense" in {
      val expense = makeExpense(date = baseDate)
      ReconciliationMatcher.matchRow(
        makeRow(date = baseDate.plusDays(3)),
        RowRuleVerdict.NoRule,
        List(expense)
      ) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "reject a row dated 4 days after the expense" in {
      val expense = makeExpense(date = baseDate)
      ReconciliationMatcher.matchRow(
        makeRow(date = baseDate.plusDays(4)),
        RowRuleVerdict.NoRule,
        List(expense)
      ) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "reject a row dated 1 day before the expense" in {
      val expense = makeExpense(date = baseDate)
      ReconciliationMatcher.matchRow(
        makeRow(date = baseDate.minusDays(1)),
        RowRuleVerdict.NoRule,
        List(expense)
      ) must be(ReconciliationMatcher.CreateNew)
    }

    "exclude expenses that are already linked to a row" in {
      val expense = makeExpense(rowIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(expense)) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "reject an expense with a different merchantId under a SetMerchant verdict" in {
      val expense = makeExpense(mId = Some(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.SetMerchant(merchantId), List(expense)) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "accept an expense with the same merchantId under a SetMerchant verdict" in {
      val expense = makeExpense(mId = Some(merchantId))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.SetMerchant(merchantId), List(expense)) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "accept an expense under a SetCategory verdict only when a split has that category" in {
      val withCategory = makeExpense(splits = List(split(UUID.randomUUID()), split(categoryId)))
      val withoutCategory = makeExpense(splits = List(split(UUID.randomUUID())))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetCategory(categoryId),
        List(withCategory, withoutCategory)
      ) must be(ReconciliationMatcher.AutoLink(withCategory.id))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.SetCategory(categoryId), List(withoutCategory)) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "match regardless of merchant and category under a NoRule verdict" in {
      val expense = makeExpense(mId = None, splits = List(split(UUID.randomUUID())))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(expense)) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }
  }
}
