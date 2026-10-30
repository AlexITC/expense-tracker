package net.wiringbits.components.pages

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.utils.ReconciliationMatcher
import org.scalatest.matchers.must.Matchers.*
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, LocalDate}
import java.util.UUID

class BankStatementReviewLogicSpec extends AnyWordSpec {

  private def sampleRow(description: String = "SUPERMARKET"): GetBankStatement.StatementRowData =
    GetBankStatement.StatementRowData(
      id = UUID.randomUUID(),
      index = 0,
      date = LocalDate.of(2024, 3, 7),
      amount = BigDecimal("-120.00"),
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

  "resultLabel" should {
    "use row description in success label" in {
      val row = sampleRow(description = "WHOLE FOODS MARKET")
      BankStatementReviewLogic.resultLabel(
        row.id,
        Map(row.id -> row.description),
        Right(())
      ) mustBe "✓ WHOLE FOODS MARKET"
    }

    "use row description in failure label" in {
      val row = sampleRow(description = "WHOLE FOODS MARKET")
      BankStatementReviewLogic.resultLabel(row.id, Map(row.id -> row.description), Left("No default category")) mustBe
        "✗ WHOLE FOODS MARKET: No default category"
    }

    "fall back to UUID prefix when row not found in map" in {
      val id = UUID.fromString("abcdef12-0000-0000-0000-000000000000")
      BankStatementReviewLogic.resultLabel(id, Map.empty, Right(())) mustBe "✓ abcdef12"
    }

    "fall back to UUID prefix in failure label when row not found" in {
      val id = UUID.fromString("abcdef12-0000-0000-0000-000000000000")
      BankStatementReviewLogic.resultLabel(id, Map.empty, Left("timeout")) mustBe "✗ abcdef12: timeout"
    }

    "use empty string description as-is in success label" in {
      val row = sampleRow(description = "")
      BankStatementReviewLogic.resultLabel(row.id, Map(row.id -> row.description), Right(())) mustBe "✓ "
    }
  }

  "suggestionsPending" should {
    def suggestions(status: AiSuggestionsStatus) = Some(AiSuggestions(status, "eu.amazon.nova-lite-v1:0", Nil))

    "be true when suggestions have not been written yet" in {
      BankStatementReviewLogic.suggestionsPending(None) mustBe true
    }

    "be true when the status is Pending" in {
      BankStatementReviewLogic.suggestionsPending(suggestions(AiSuggestionsStatus.Pending)) mustBe true
    }

    "be false when the status is Done" in {
      BankStatementReviewLogic.suggestionsPending(suggestions(AiSuggestionsStatus.Done)) mustBe false
    }

    "be false when the status is Failed" in {
      BankStatementReviewLogic.suggestionsPending(suggestions(AiSuggestionsStatus.Failed)) mustBe false
    }
  }

  "canRegenerateSuggestions" should {
    def suggestions(status: AiSuggestionsStatus) = Some(AiSuggestions(status, "eu.amazon.nova-lite-v1:0", Nil))

    "be true when suggestions have never been generated" in {
      BankStatementReviewLogic.canRegenerateSuggestions(None, pollAttemptsExhausted = false) mustBe true
    }

    "be false when the status is Pending and polling is still active" in {
      BankStatementReviewLogic.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Pending),
        pollAttemptsExhausted = false
      ) mustBe false
    }

    "be true when the status is Pending but the poll attempts are exhausted" in {
      BankStatementReviewLogic.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Pending),
        pollAttemptsExhausted = true
      ) mustBe true
    }

    "be true when the status is Done" in {
      BankStatementReviewLogic.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Done),
        pollAttemptsExhausted = false
      ) mustBe true
    }

    "be true when the status is Failed" in {
      BankStatementReviewLogic.canRegenerateSuggestions(
        suggestions(AiSuggestionsStatus.Failed),
        pollAttemptsExhausted = false
      ) mustBe true
    }
  }

  "resolveImport" should {
    def merchant(id: UUID, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
      GetMerchants.Response.Merchant(id, "Merchant", None, categoryId, None, reviewed = true, Instant.EPOCH)

    def rule(action: ImportRuleActionKind, pattern: String, merchantId: Option[UUID]): ImportRuleMatcher.Rule = {
      val a = action match {
        case ImportRuleActionKind.SetMerchant => ImportRuleAction.SetMerchant(merchantId.getOrElse(UUID.randomUUID()))
        case ImportRuleActionKind.SetCategory => ImportRuleAction.SetCategory(UUID.randomUUID())
        case ImportRuleActionKind.Ignore => ImportRuleAction.Ignore
      }
      ImportRuleMatcher.Rule(UUID.randomUUID(), a, pattern)
    }

    def done(rows: AiSuggestions.RowSuggestion*): Option[AiSuggestions] =
      Some(AiSuggestions(AiSuggestionsStatus.Done, "eu.amazon.nova-lite-v1:0", rows.toList))

    "take the category from a rule-matched merchant, not AI" in {
      val row = sampleRow("SUPERMARKET")
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      BankStatementReviewLogic.resolveImport(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, Some(c))),
        aiSuggestions = None,
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), c, categoryFromAi = false))
    }

    "fall back to the AI category with no merchant when no rule matches" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      BankStatementReviewLogic.resolveImport(
        Nil,
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(None, c, categoryFromAi = true))
    }

    "use the AI-suggested merchant and category when no rule matches" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      val m = UUID.randomUUID()
      BankStatementReviewLogic.resolveImport(
        Nil,
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), Some(m))),
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), c, categoryFromAi = true))
    }

    "keep the rule merchant but take the category from AI when the merchant has no default category" in {
      val row = sampleRow("SUPERMARKET")
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      BankStatementReviewLogic.resolveImport(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, None)),
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), c, categoryFromAi = true))
    }

    "resolve to None for an Ignore rule even when AI has a suggestion" in {
      val row = sampleRow("SUPERMARKET")
      val c = UUID.randomUUID()
      BankStatementReviewLogic.resolveImport(
        List(rule(ImportRuleActionKind.Ignore, "SUPERMARKET", None)),
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(c), None)),
        row
      ) mustBe None
    }

    "resolve a SetCategory rule to its category with no merchant, ignoring the AI suggestion" in {
      val row = sampleRow("SUPERMARKET")
      val c = UUID.randomUUID()
      val setCategory =
        ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory(c), "SUPERMARKET")
      BankStatementReviewLogic.resolveImport(
        List(setCategory),
        Nil,
        done(AiSuggestions.RowSuggestion(row.id, Some(UUID.randomUUID()), Some(UUID.randomUUID()))),
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(None, c, categoryFromAi = false))
    }

    "prefer a SetMerchant rule over a longer SetCategory rule on the same row" in {
      val row = sampleRow("SUPERMARKET 0042")
      val m = UUID.randomUUID()
      val merchantCategory = UUID.randomUUID()
      val setCategory =
        ImportRuleMatcher.Rule(
          UUID.randomUUID(),
          ImportRuleAction.SetCategory(UUID.randomUUID()),
          "SUPERMARKET 0042"
        )
      BankStatementReviewLogic.resolveImport(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m)), setCategory),
        List(merchant(m, Some(merchantCategory))),
        aiSuggestions = None,
        row
      ) mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), merchantCategory, categoryFromAi = false))
    }

    "ignore AI suggestions that are still Pending" in {
      val row = sampleRow("UNKNOWN SHOP")
      val c = UUID.randomUUID()
      val pending =
        Some(AiSuggestions(AiSuggestionsStatus.Pending, "m", List(AiSuggestions.RowSuggestion(row.id, Some(c), None))))
      BankStatementReviewLogic.resolveImport(Nil, Nil, pending, row) mustBe None
    }

    "resolve to None when nothing matches and there is no suggestion" in {
      BankStatementReviewLogic.resolveImport(Nil, Nil, None, sampleRow("UNKNOWN SHOP")) mustBe None
    }
  }

  "primaryActionFor" should {
    import BankStatementReviewLogic.{PrimaryAction, primaryActionFor}
    val negative = sampleRow()
    val positive = sampleRow().copy(amount = BigDecimal("45.00"))
    val setMerchant = RowRuleVerdict.SetMerchant(UUID.randomUUID())

    "review a ready negative row with a SetMerchant verdict as an expense" in {
      primaryActionFor(negative, isReady = true, setMerchant) mustBe PrimaryAction.ReviewExpense
    }

    "review a ready positive row as a refund" in {
      primaryActionFor(positive, isReady = true, RowRuleVerdict.NoRule) mustBe PrimaryAction.ReviewRefund
    }

    "ignore a ready row with an Ignore verdict in one click" in {
      primaryActionFor(negative, isReady = true, RowRuleVerdict.Ignore) mustBe PrimaryAction.IgnoreByRule
    }

    "show Linked for a linked row even when it is ready" in {
      primaryActionFor(negative.copy(expenseId = Some(UUID.randomUUID())), isReady = true, setMerchant) mustBe
        PrimaryAction.Linked
      primaryActionFor(positive.copy(refundId = Some(UUID.randomUUID())), isReady = true, setMerchant) mustBe
        PrimaryAction.Linked
    }

    "offer Unignore for an ignored row" in {
      primaryActionFor(negative.copy(ignoredAt = Some(Instant.now())), isReady = false, RowRuleVerdict.Ignore) mustBe
        PrimaryAction.Unignore
    }

    "create an expense for a not-ready negative row" in {
      primaryActionFor(negative, isReady = false, RowRuleVerdict.NoRule) mustBe PrimaryAction.CreateExpense
    }

    "create a refund for a not-ready positive row" in {
      primaryActionFor(positive, isReady = false, RowRuleVerdict.NoRule) mustBe PrimaryAction.CreateRefund
    }
  }

  "rowInfo" should {
    import BankStatementReviewLogic.rowInfo

    def merchant(id: UUID, name: String, categoryId: Option[UUID]): GetMerchants.Response.Merchant =
      GetMerchants.Response.Merchant(id, name, None, categoryId, None, reviewed = true, Instant.EPOCH)

    def rule(action: ImportRuleActionKind, pattern: String, merchantId: Option[UUID]): ImportRuleMatcher.Rule = {
      val a = action match {
        case ImportRuleActionKind.SetMerchant => ImportRuleAction.SetMerchant(merchantId.getOrElse(UUID.randomUUID()))
        case ImportRuleActionKind.SetCategory => ImportRuleAction.SetCategory(UUID.randomUUID())
        case ImportRuleActionKind.Ignore => ImportRuleAction.Ignore
      }
      ImportRuleMatcher.Rule(UUID.randomUUID(), a, pattern)
    }

    "mark an unprocessed negative row with an Ignore rule as ready, with nothing to import" in {
      val info = rowInfo(List(rule(ImportRuleActionKind.Ignore, "SUPERMARKET", None)), Nil, Nil, None, sampleRow())
      info.verdict mustBe RowRuleVerdict.Ignore
      info.resolved mustBe None
      info.isReady mustBe true
    }

    "mark a row with a resolved import as ready and expose the merchant and category names" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val info = rowInfo(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, "Walmart", Some(c))),
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        sampleRow()
      )
      info.verdict mustBe RowRuleVerdict.SetMerchant(m)
      info.resolved mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), c, categoryFromAi = false))
      info.isReady mustBe true
      info.merchantName mustBe Some("Walmart")
      info.categoryName mustBe Some("Groceries")
    }

    "resolve a row matched only by a merchant name to that merchant and its category" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val merchants = List(merchant(m, "Supermarket", Some(c)))
      val info = rowInfo(
        ImportRuleMatcher.effectiveRules(Nil, merchants.map(mm => mm.id -> mm.name)),
        merchants,
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        sampleRow("POS SUPERMARKET 0042")
      )
      info.verdict mustBe RowRuleVerdict.SetMerchant(m)
      info.resolved mustBe Some(BankStatementReviewLogic.ResolvedImport(Some(m), c, categoryFromAi = false))
      info.isReady mustBe true
      info.merchantName mustBe Some("Supermarket")
      info.categoryName mustBe Some("Groceries")
    }

    "mark a row with a SetCategory rule as ready with the rule's category and no merchant, ignoring AI" in {
      val c = UUID.randomUUID()
      val row = sampleRow()
      val ai = Some(
        AiSuggestions(
          AiSuggestionsStatus.Done,
          "m",
          List(AiSuggestions.RowSuggestion(row.id, Some(UUID.randomUUID()), Some(UUID.randomUUID())))
        )
      )
      val info = rowInfo(
        List(ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory(c), "SUPERMARKET")),
        Nil,
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        ai,
        row
      )
      info.verdict mustBe RowRuleVerdict.SetCategory(c)
      info.resolved mustBe Some(BankStatementReviewLogic.ResolvedImport(None, c, categoryFromAi = false))
      info.isReady mustBe true
      info.merchantName mustBe None
      info.categoryName mustBe Some("Groceries")
    }

    "take the category name from an AI suggestion" in {
      val c = UUID.randomUUID()
      val row = sampleRow("UNKNOWN SHOP")
      val ai = Some(
        AiSuggestions(AiSuggestionsStatus.Done, "m", List(AiSuggestions.RowSuggestion(row.id, Some(c), None)))
      )
      val info = rowInfo(Nil, Nil, List(GetCategories.Response.Category(c, "Dining", Instant.EPOCH)), ai, row)
      info.resolved.map(_.categoryFromAi) mustBe Some(true)
      info.merchantName mustBe None
      info.categoryName mustBe Some("Dining")
    }

    "expose the linked expense's merchant and category names" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val row =
        sampleRow().copy(expenseId = Some(UUID.randomUUID()), expenseMerchantId = Some(m), expenseCategoryId = Some(c))
      val info = rowInfo(
        Nil,
        List(merchant(m, "Walmart", None)),
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        row
      )
      info.linkedMerchantName mustBe Some("Walmart")
      info.linkedCategory mustBe BankStatementReviewLogic.LinkedCategory.Named("Groceries")
    }

    "not mark a row ready when nothing resolves" in {
      val info = rowInfo(Nil, Nil, Nil, None, sampleRow("UNKNOWN SHOP"))
      info.isReady mustBe false
      info.merchantName mustBe None
      info.categoryName mustBe None
    }

    "not mark an already linked or a positive row ready" in {
      val rules = List(rule(ImportRuleActionKind.Ignore, "SUPERMARKET", None))
      rowInfo(rules, Nil, Nil, None, sampleRow().copy(expenseId = Some(UUID.randomUUID()))).isReady mustBe false
      rowInfo(rules, Nil, Nil, None, sampleRow().copy(amount = BigDecimal("10.00"))).isReady mustBe false
    }
  }

  "menuItemsFor" should {
    import BankStatementReviewLogic.{RowMenuItem, menuItemsFor}

    "offer only Unlink for a row linked to an expense" in {
      menuItemsFor(sampleRow().copy(expenseId = Some(UUID.randomUUID()))) mustBe List(RowMenuItem.Unlink)
    }

    "offer only UnlinkRefund for a row linked to a refund" in {
      menuItemsFor(sampleRow().copy(amount = BigDecimal("45.00"), refundId = Some(UUID.randomUUID()))) mustBe
        List(RowMenuItem.UnlinkRefund)
    }

    "offer only Unignore for an ignored row" in {
      menuItemsFor(sampleRow().copy(ignoredAt = Some(Instant.now()))) mustBe List(RowMenuItem.Unignore)
    }

    "offer link, rule and ignore for an unprocessed row" in {
      menuItemsFor(sampleRow("UNKNOWN SHOP")) mustBe
        List(RowMenuItem.LinkExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)
    }
  }

  "linkedCategoryDisplay" should {
    import BankStatementReviewLogic.{LinkedCategory, linkedCategoryDisplay}
    val c = UUID.randomUUID()
    val categories = List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH))
    val linked = sampleRow().copy(expenseId = Some(UUID.randomUUID()))

    "resolve a single category to its name" in {
      linkedCategoryDisplay(linked.copy(expenseCategoryId = Some(c)), categories) mustBe
        LinkedCategory.Named("Groceries")
    }

    "show Multiple for a linked expense without a single category" in {
      linkedCategoryDisplay(linked, categories) mustBe LinkedCategory.Multiple
    }

    "be Unknown when the category is not in the list" in {
      linkedCategoryDisplay(linked.copy(expenseCategoryId = Some(UUID.randomUUID())), categories) mustBe
        LinkedCategory.Unknown
    }

    "be Unknown for a refund-only link" in {
      linkedCategoryDisplay(sampleRow().copy(refundId = Some(UUID.randomUUID())), categories) mustBe
        LinkedCategory.Unknown
    }
  }

  "linkedMerchantName" should {
    import BankStatementReviewLogic.linkedMerchantName
    val m = UUID.randomUUID()
    val merchants =
      List(GetMerchants.Response.Merchant(m, "Walmart", None, None, None, reviewed = true, Instant.EPOCH))
    val linked = sampleRow().copy(expenseId = Some(UUID.randomUUID()))

    "resolve the linked expense's merchant to its name" in {
      linkedMerchantName(linked.copy(expenseMerchantId = Some(m)), merchants) mustBe Some("Walmart")
    }

    "be None when the expense has no merchant" in {
      linkedMerchantName(linked, merchants) mustBe None
    }

    "be None when the merchant is not in the list" in {
      linkedMerchantName(linked.copy(expenseMerchantId = Some(UUID.randomUUID())), merchants) mustBe None
    }
  }

  "bulkImportSummary" should {
    "list every non-zero count" in {
      BankStatementReviewLogic.bulkImportSummary(linked = 2, created = 3, ignored = 1, failed = 1) mustBe
        "2 linked, 3 created, 1 ignored, 1 failed"
    }

    "omit zero counts" in {
      BankStatementReviewLogic.bulkImportSummary(linked = 0, created = 4, ignored = 0, failed = 2) mustBe
        "4 created, 2 failed"
    }

    "say nothing was processed when every count is zero" in {
      BankStatementReviewLogic.bulkImportSummary(0, 0, 0, 0) mustBe "No rows processed"
    }
  }

  "bulkRowDecision" should {
    import BankStatementReviewLogic.{BulkRowDecision, LinkedCategory, ResolvedImport, RowInfo}

    val categoryId = UUID.randomUUID()
    val expenseId = UUID.randomUUID()
    def info(verdict: RowRuleVerdict.Verdict, resolved: Option[ResolvedImport]): Option[RowInfo] =
      Some(
        RowInfo(
          verdict = verdict,
          resolved = resolved,
          isReady = true,
          merchantName = None,
          categoryName = None,
          linkedMerchantName = None,
          linkedCategory = LinkedCategory.Unknown
        )
      )
    val resolved = ResolvedImport(merchantId = None, categoryId = categoryId, categoryFromAi = false)

    "ignore a row with an Ignore verdict" in {
      BankStatementReviewLogic.bulkRowDecision(info(RowRuleVerdict.Ignore, None), None, Set.empty) mustBe
        BulkRowDecision.Ignore
    }

    "ignore a row with an Ignore verdict even when it has an expense match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.Ignore, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        Set.empty
      ) mustBe BulkRowDecision.Ignore
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.Ignore, Some(resolved)),
        Some(ReconciliationMatcher.NeedsReview(List(UUID.randomUUID(), UUID.randomUUID()))),
        Set.empty
      ) mustBe BulkRowDecision.Ignore
    }

    "link a row with a unique match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        Set.empty
      ) mustBe BulkRowDecision.Link(expenseId)
    }

    "fail a row whose unique match was already linked in this run" in {
      val decision = BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        Set(expenseId)
      )
      decision match {
        case BulkRowDecision.Fail(msg) => msg must include("already linked")
        case other => fail(s"Expected Fail, got $other")
      }
    }

    "fail a row with several matches, naming how many" in {
      val decision = BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.NeedsReview(List(UUID.randomUUID(), UUID.randomUUID()))),
        Set.empty
      )
      decision match {
        case BulkRowDecision.Fail(msg) => msg must startWith("Matches 2 existing expenses")
        case other => fail(s"Expected Fail, got $other")
      }
    }

    "create an expense when nothing matches" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.CreateNew),
        Set.empty
      ) mustBe BulkRowDecision.Create(resolved)
    }

    "fail with No category when the row resolves to nothing" in {
      BankStatementReviewLogic.bulkRowDecision(info(RowRuleVerdict.NoRule, None), None, Set.empty) mustBe
        BulkRowDecision.Fail("No category")
    }
  }

  "expenseMatches" should {
    import BankStatementReviewLogic.{LinkedCategory, RowInfo}

    def info(verdict: RowRuleVerdict.Verdict, isReady: Boolean): RowInfo =
      RowInfo(
        verdict = verdict,
        resolved = None,
        isReady = isReady,
        merchantName = None,
        categoryName = None,
        linkedMerchantName = None,
        linkedCategory = LinkedCategory.Unknown
      )

    "only match ready, non-ignored debit rows" in {
      val eligible = sampleRow()
      val refund = sampleRow().copy(amount = BigDecimal("120.00"))
      val notReady = sampleRow()
      val ignored = sampleRow()
      val expense = GetExpenses.Response.Expense(
        id = UUID.randomUUID(),
        amount = BigDecimal("120.00"),
        date = eligible.date,
        time = None,
        merchantId = None,
        merchantName = None,
        notes = None,
        splits = Nil,
        createdAt = Instant.now()
      )
      val infos = Map(
        eligible.id -> info(RowRuleVerdict.NoRule, isReady = true),
        refund.id -> info(RowRuleVerdict.NoRule, isReady = true),
        notReady.id -> info(RowRuleVerdict.NoRule, isReady = false),
        ignored.id -> info(RowRuleVerdict.Ignore, isReady = true)
      )
      BankStatementReviewLogic.expenseMatches(
        List(eligible, refund, notReady, ignored),
        infos,
        ReconciliationMatcher.unlinkedByDate(List(expense))
      ) mustBe Map(eligible.id -> ReconciliationMatcher.AutoLink(expense.id))
    }
  }

  "filterRows" should {
    import BankStatementReviewLogic.{LinkedCategory, RowInfo, filterRows}

    val open = sampleRow()
    val ready = sampleRow()
    val ignored = sampleRow().copy(ignoredAt = Some(Instant.now()))
    val linkedExpense = sampleRow().copy(expenseId = Some(UUID.randomUUID()))
    val linkedRefund = sampleRow().copy(refundId = Some(UUID.randomUUID()))
    val rows = List(open, ready, ignored, linkedExpense, linkedRefund)
    val readyInfo = RowInfo(
      verdict = RowRuleVerdict.NoRule,
      resolved = None,
      isReady = true,
      merchantName = None,
      categoryName = None,
      linkedMerchantName = None,
      linkedCategory = LinkedCategory.Unknown
    )
    val infos = Map(ready.id -> readyInfo, open.id -> readyInfo.copy(isReady = false))

    "keep only ready rows on the ready tab" in {
      filterRows(rows, "ready", infos) mustBe List(ready)
    }

    "keep open rows on the unprocessed tab" in {
      filterRows(rows, "unprocessed", infos) mustBe List(open, ready)
    }

    "keep only ignored rows on the ignored tab" in {
      filterRows(rows, "ignored", infos) mustBe List(ignored)
    }

    "keep expense- and refund-linked rows on the linked tab" in {
      filterRows(rows, "linked", infos) mustBe List(linkedExpense, linkedRefund)
    }

    "keep every row on the all tab" in {
      filterRows(rows, "all", infos) mustBe rows
    }

    "keep open rows for an unknown tab" in {
      filterRows(rows, "bogus", infos) mustBe List(open, ready)
    }
  }

  "candidateExpenseWindow" should {
    def statement(rowDates: LocalDate*): GetBankStatement.Response =
      GetBankStatement.Response(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        documentFilename = "statement.pdf",
        documentStatus = DocumentStatus.Parsed,
        bankName = "Bank",
        productType = "credit",
        currency = "USD",
        accountLast4 = "1234",
        periodStart = LocalDate.of(2024, 3, 1),
        periodEnd = LocalDate.of(2024, 3, 31),
        statementRows = rowDates.toList.map(d => sampleRow().copy(date = d)),
        createdAt = Instant.now(),
        extractionInfo = None,
        aiSuggestions = None
      )

    "span the period, starting the match tolerance before it" in {
      val window = BankStatementReviewLogic.candidateExpenseWindow(statement(LocalDate.of(2024, 3, 10)))
      window mustBe (LocalDate.of(2024, 2, 27), LocalDate.of(2024, 3, 31))
    }

    "start before a row dated before the period" in {
      val window = BankStatementReviewLogic.candidateExpenseWindow(statement(LocalDate.of(2024, 2, 20)))
      window mustBe (LocalDate.of(2024, 2, 17), LocalDate.of(2024, 3, 31))
    }

    "end at a row dated after the period" in {
      val window = BankStatementReviewLogic.candidateExpenseWindow(statement(LocalDate.of(2024, 4, 5)))
      window mustBe (LocalDate.of(2024, 2, 27), LocalDate.of(2024, 4, 5))
    }
  }
}
