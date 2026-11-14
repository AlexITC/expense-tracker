package net.wiringbits.utils

import net.wiringbits.api.models.bankstatements.{CreateExpenseFromRow, GetBankStatement}
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.utils.ReconciliationMatcher.ReceiptMatch
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
    action = ImportRuleAction.SetMerchant(merchantId, categoryId = None, description = None),
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
      rowIds: List[UUID] = Nil,
      receiptIds: List[UUID] = Nil
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
      receiptIds = receiptIds,
      rowIds = rowIds
    )

  private def makeReceipt(
      amount: Option[BigDecimal] = Some(BigDecimal("100.00")),
      date: Option[LocalDate] = Some(baseDate),
      merchantName: Option[String] = Some("Amazon")
  ): GetReceipts.Response.Receipt =
    GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt.pdf",
      documentStatus = DocumentStatus.Parsed,
      amount = amount,
      date = date,
      merchantName = merchantName,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.now()
    )

  "qualifyingCreateRequests" should {

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

    "produce AutoLink for a receipt-backed expense whose merchant differs from the rule's merchant" in {
      val row = makeRow()
      val expense = makeExpense(mId = Some(UUID.randomUUID()), receiptIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "produce AutoLink for a receipt-backed expense when the row matches no rule" in {
      val row = makeRow(description = "UNRECOGNIZED VENDOR XYZ")
      val expense = makeExpense(receiptIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(expense), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(expense.id))
    }

    "produce NeedsReview when two receipt-backed expenses match" in {
      val row = makeRow()
      val e1 = makeExpense(receiptIds = List(UUID.randomUUID()))
      val e2 = makeExpense(mId = None, receiptIds = List(UUID.randomUUID()))
      val plain = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(e1, e2, plain), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.NeedsReview(List(e1.id, e2.id)))
    }

    "fall back to the rule-based match when the receipt-backed expense is outside the ±3-day window" in {
      val row = makeRow(date = baseDate)
      val outside = makeExpense(date = baseDate.plusDays(4), receiptIds = List(UUID.randomUUID()))
      val ruleMatch = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(outside, ruleMatch), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(ruleMatch.id))
    }

    "fall back to the rule-based match when the receipt-backed expense only has a near amount" in {
      val row = makeRow()
      val near = makeExpense(amount = BigDecimal("100.50"), receiptIds = List(UUID.randomUUID()))
      val ruleMatch = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(row), List(near, ruleMatch), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.AutoLink(ruleMatch.id))
    }

    "produce CreateNew when the only receipt-backed expense has a near amount and no rule matches" in {
      val row = makeRow(description = "UNRECOGNIZED VENDOR XYZ")
      val near = makeExpense(amount = BigDecimal("100.50"), receiptIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(near), List(matchingRule), Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "produce CreateNew for a receipt-backed expense already linked to another row" in {
      val row = makeRow(description = "UNRECOGNIZED VENDOR XYZ")
      val linked = makeExpense(rowIds = List(UUID.randomUUID()), receiptIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(row), List(linked), Nil, Nil)
      result(row.id) must be(ReconciliationMatcher.CreateNew)
    }

    "auto-link a receipt-backed expense to only the first of two same-amount rows" in {
      val first = makeRow(description = "COFFEE SHOP")
      val second = makeRow(description = "COFFEE SHOP")
      val expense = makeExpense(receiptIds = List(UUID.randomUUID()))
      val result = ReconciliationMatcher.reconcile(List(first, second), List(expense), Nil, Nil)
      result(first.id) must be(ReconciliationMatcher.AutoLink(expense.id))
      result(second.id) must be(ReconciliationMatcher.CreateNew)
    }

    "auto-link a rule-matched expense to only the first of two same-amount rows" in {
      val first = makeRow()
      val second = makeRow()
      val expense = makeExpense()
      val result = ReconciliationMatcher.reconcile(List(first, second), List(expense), List(matchingRule), Nil)
      result(first.id) must be(ReconciliationMatcher.AutoLink(expense.id))
      result(second.id) must be(ReconciliationMatcher.CreateNew)
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
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(merchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "accept an expense with the same merchantId under a SetMerchant verdict" in {
      val expense = makeExpense(mId = Some(merchantId))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(merchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "accept an expense under a SetCategory verdict only when a split has that category" in {
      val withCategory = makeExpense(splits = List(split(UUID.randomUUID()), split(categoryId)))
      val withoutCategory = makeExpense(splits = List(split(UUID.randomUUID())))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetCategory(categoryId, description = None),
        List(withCategory, withoutCategory)
      ) must be(ReconciliationMatcher.AutoLink(withCategory.id))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetCategory(categoryId, description = None),
        List(withoutCategory)
      ) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "match regardless of merchant and category under a NoRule verdict" in {
      val expense = makeExpense(mId = None, splits = List(split(UUID.randomUUID())))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(expense)) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "auto-link a receipt-backed expense even when its merchant disagrees with the verdict" in {
      val otherMerchantId = UUID.randomUUID()
      val expense = makeExpense(mId = Some(merchantId), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(otherMerchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "not match a receipt-backed expense outside the window" in {
      val otherMerchantId = UUID.randomUUID()
      val expense =
        makeExpense(date = baseDate.minusDays(4), mId = Some(merchantId), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(otherMerchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "propose review when several receipt-backed expenses match exactly" in {
      val otherMerchantId = UUID.randomUUID()
      val e1 = makeExpense(mId = Some(merchantId), receiptIds = List(UUID.randomUUID()))
      val e2 = makeExpense(mId = Some(merchantId), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(otherMerchantId, categoryId = None, description = None),
        List(e1, e2)
      ) must be(
        ReconciliationMatcher.NeedsReview(List(e1.id, e2.id))
      )
    }

    "prefer an exact verdict-matching expense over a near receipt-backed one" in {
      val exact = makeExpense(mId = Some(merchantId))
      val nearWithReceipt =
        makeExpense(amount = BigDecimal("100.50"), mId = Some(UUID.randomUUID()), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(merchantId, categoryId = None, description = None),
        List(nearWithReceipt, exact)
      ) must be(ReconciliationMatcher.AutoLink(exact.id))
    }

    "not match an expense without receipts whose merchant disagrees with the verdict" in {
      val otherMerchantId = UUID.randomUUID()
      val expense = makeExpense(mId = Some(merchantId))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(otherMerchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "auto-link a near-amount receipt-backed expense whose merchant disagrees with the verdict" in {
      val otherMerchantId = UUID.randomUUID()
      val expense = makeExpense(amount = BigDecimal("100.50"), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(
        makeRow(),
        RowRuleVerdict.SetMerchant(otherMerchantId, categoryId = None, description = None),
        List(expense)
      ) must be(
        ReconciliationMatcher.AutoLink(expense.id)
      )
    }

    "prefer the receipt-backed expense among near-amount candidates" in {
      val plain = makeExpense(amount = BigDecimal("100.50"))
      val withReceipt = makeExpense(amount = BigDecimal("100.50"), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(plain, withReceipt)) must be(
        ReconciliationMatcher.AutoLink(withReceipt.id)
      )
    }

    "not match a receipt-backed expense already linked to another row" in {
      val linked = makeExpense(rowIds = List(UUID.randomUUID()), receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(linked)) must be(
        ReconciliationMatcher.CreateNew
      )
    }

    "prefer the receipt-backed expense among exact candidates" in {
      val plain = makeExpense()
      val withReceipt = makeExpense(receiptIds = List(UUID.randomUUID()))
      ReconciliationMatcher.matchRow(makeRow(), RowRuleVerdict.NoRule, List(plain, withReceipt)) must be(
        ReconciliationMatcher.AutoLink(withReceipt.id)
      )
    }
  }

  "candidatesFor" should {
    "return unlinked expenses dated 0 to 3 days before the row" in {
      val row = makeRow()
      val inWindow = List(0L, 3L).map(d => makeExpense(date = baseDate.minusDays(d)))
      val outside = List(makeExpense(date = baseDate.minusDays(4)), makeExpense(date = baseDate.plusDays(1)))
      val linked = makeExpense(rowIds = List(UUID.randomUUID()))
      val byDate = ReconciliationMatcher.unlinkedByDate(inWindow ++ outside :+ linked)
      ReconciliationMatcher.candidatesFor(row, byDate).map(_.id).toSet mustBe inWindow.map(_.id).toSet
    }
  }

  "matchReceipt" should {
    "require an exact amount" in {
      val receipt = makeReceipt(amount = Some(BigDecimal("100.01")))
      ReconciliationMatcher.matchReceipt(makeRow(), List(receipt)) mustBe ReceiptMatch.NoMatch
    }

    "match a debit row against the receipt's positive amount" in {
      val receipt = makeReceipt()
      ReconciliationMatcher.matchReceipt(makeRow(amount = BigDecimal("-100.00")), List(receipt)) mustBe
        ReceiptMatch.Single(receipt.id)
    }

    "match when the row posts 0 to MaxDateDifferenceDays days after the receipt" in {
      List(0L, ReconciliationMatcher.MaxDateDifferenceDays).foreach { d =>
        val receipt = makeReceipt(date = Some(baseDate.minusDays(d)))
        ReconciliationMatcher.matchReceipt(makeRow(), List(receipt)) mustBe ReceiptMatch.Single(receipt.id)
      }
    }

    "not match when the row posts before the receipt" in {
      val receipt = makeReceipt(date = Some(baseDate.plusDays(1)))
      ReconciliationMatcher.matchReceipt(makeRow(), List(receipt)) mustBe ReceiptMatch.NoMatch
    }

    "not match when the row posts more than MaxDateDifferenceDays days after the receipt" in {
      val receipt = makeReceipt(date = Some(baseDate.minusDays(ReconciliationMatcher.MaxDateDifferenceDays + 1)))
      ReconciliationMatcher.matchReceipt(makeRow(), List(receipt)) mustBe ReceiptMatch.NoMatch
    }

    "exclude a receipt without a date even when the amount is exact" in {
      ReconciliationMatcher.matchReceipt(makeRow(), List(makeReceipt(date = None))) mustBe ReceiptMatch.NoMatch
    }

    "exclude a receipt without an amount" in {
      ReconciliationMatcher.matchReceipt(makeRow(), List(makeReceipt(amount = None))) mustBe ReceiptMatch.NoMatch
    }

    "return Single for the only candidate among non-matching receipts" in {
      val matching = makeReceipt()
      val others = List(makeReceipt(amount = Some(BigDecimal("55.00"))), makeReceipt(date = None))
      ReconciliationMatcher.matchReceipt(makeRow(), matching :: others) mustBe ReceiptMatch.Single(matching.id)
    }

    "return Ambiguous with every candidate when several match" in {
      val first = makeReceipt()
      val second = makeReceipt(date = Some(baseDate.minusDays(1)))
      ReconciliationMatcher.matchReceipt(makeRow(), List(first, second)) mustBe
        ReceiptMatch.Ambiguous(List(first.id, second.id))
    }

    "ignore the merchant name" in {
      val receipt = makeReceipt(merchantName = Some("Corner Bakery"))
      ReconciliationMatcher.matchReceipt(makeRow(), List(receipt)) mustBe ReceiptMatch.Single(receipt.id)
    }
  }
}
