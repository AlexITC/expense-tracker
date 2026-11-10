package net.wiringbits.components.pages.bankstatementreview

import net.wiringbits.api.models.bankstatements.{AiSuggestions, AiSuggestionsStatus, GetBankStatement}
import net.wiringbits.api.models.categories.GetCategories
import net.wiringbits.api.models.expenses.GetExpenses
import net.wiringbits.api.models.documents.DocumentStatus
import net.wiringbits.api.models.merchants.{GetMerchants, ImportRuleAction, ImportRuleActionKind}
import net.wiringbits.api.models.receipts.GetReceipts
import net.wiringbits.api.utils.{ImportRuleMatcher, RowRuleVerdict}
import net.wiringbits.components.pages.bankstatements.BankStatementsLogic
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
    import BankStatementReviewLogic.{LinkedCategory, MatchedExpense, PrimaryAction, primaryActionFor}
    val negative = sampleRow()
    val match1 = MatchedExpense(UUID.randomUUID(), None, LinkedCategory.Unknown)
    val positive = sampleRow().copy(amount = BigDecimal("45.00"))
    val setMerchant = RowRuleVerdict.SetMerchant(UUID.randomUUID())

    "review a ready negative row with a SetMerchant verdict as an expense" in {
      primaryActionFor(negative, isReady = true, setMerchant, None) mustBe PrimaryAction.ReviewExpense
    }

    "review a ready positive row as a refund" in {
      primaryActionFor(positive, isReady = true, RowRuleVerdict.NoRule, None) mustBe PrimaryAction.ReviewRefund
    }

    "ignore a ready row with an Ignore verdict in one click" in {
      primaryActionFor(negative, isReady = true, RowRuleVerdict.Ignore, None) mustBe PrimaryAction.IgnoreByRule
    }

    "show Linked for a linked row even when it is ready" in {
      primaryActionFor(negative.copy(expenseId = Some(UUID.randomUUID())), isReady = true, setMerchant, None) mustBe
        PrimaryAction.Linked
      primaryActionFor(positive.copy(refundId = Some(UUID.randomUUID())), isReady = true, setMerchant, None) mustBe
        PrimaryAction.Linked
    }

    "offer Unignore for an ignored row" in {
      primaryActionFor(
        negative.copy(ignoredAt = Some(Instant.now())),
        isReady = false,
        RowRuleVerdict.Ignore,
        None
      ) mustBe
        PrimaryAction.Unignore
    }

    "create an expense for a not-ready negative row" in {
      primaryActionFor(negative, isReady = false, RowRuleVerdict.NoRule, None) mustBe PrimaryAction.CreateExpense
    }

    "create a refund for a not-ready positive row" in {
      primaryActionFor(positive, isReady = false, RowRuleVerdict.NoRule, None) mustBe PrimaryAction.CreateRefund
    }

    "confirm the link for a ready negative row with an expense match" in {
      primaryActionFor(
        negative,
        isReady = true,
        setMerchant,
        matchedExpense = Some(match1)
      ) mustBe PrimaryAction.ConfirmLink
    }

    "review the expense for a ready negative row without an expense match" in {
      primaryActionFor(negative, isReady = true, setMerchant, matchedExpense = None) mustBe
        PrimaryAction.ReviewExpense
    }

    "ignore the expense match for Ignore verdicts, credits, ignored and linked rows" in {
      primaryActionFor(negative, isReady = true, RowRuleVerdict.Ignore, matchedExpense = Some(match1)) mustBe
        PrimaryAction.IgnoreByRule
      primaryActionFor(positive, isReady = true, RowRuleVerdict.NoRule, matchedExpense = Some(match1)) mustBe
        PrimaryAction.ReviewRefund
      primaryActionFor(
        negative.copy(ignoredAt = Some(Instant.now())),
        isReady = false,
        RowRuleVerdict.NoRule,
        matchedExpense = Some(match1)
      ) mustBe PrimaryAction.Unignore
      primaryActionFor(
        negative.copy(expenseId = Some(UUID.randomUUID())),
        isReady = true,
        setMerchant,
        matchedExpense = Some(match1)
      ) mustBe PrimaryAction.Linked
    }
  }

  "matchedExpense" should {
    import BankStatementReviewLogic.{LinkedCategory, MatchedExpense, matchedExpense}
    val groceries = UUID.randomUUID()
    val dining = UUID.randomUUID()
    val categories = List(
      GetCategories.Response.Category(groceries, "Groceries", Instant.EPOCH),
      GetCategories.Response.Category(dining, "Dining", Instant.EPOCH)
    )
    val merchantId = UUID.randomUUID()
    val merchants =
      List(GetMerchants.Response.Merchant(merchantId, "Corner Shop", None, None, None, reviewed = true, Instant.EPOCH))

    def split(categoryId: UUID) = GetExpenses.Response.Split(UUID.randomUUID(), categoryId, BigDecimal("5.00"))
    def expense(
        splits: List[GetExpenses.Response.Split],
        merchantId: Option[UUID] = None,
        merchantName: Option[String] = None
    ) = GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("10.00"),
      date = LocalDate.of(2024, 3, 7),
      time = None,
      merchantId = merchantId,
      merchantName = merchantName,
      notes = None,
      splits = splits,
      createdAt = Instant.EPOCH
    )
    def autoLink(e: GetExpenses.Response.Expense) = Some(ReconciliationMatcher.AutoLink(e.id))

    "label a single-category expense with its category and merchant name" in {
      val e = expense(List(split(groceries), split(groceries)), merchantName = Some("Corner Shop"))
      matchedExpense(autoLink(e), List(e), categories, Nil) mustBe
        Some(MatchedExpense(e.id, Some("Corner Shop"), LinkedCategory.Named("Groceries")))
    }

    "label a multi-category expense as Multiple" in {
      val e = expense(List(split(groceries), split(dining)))
      matchedExpense(autoLink(e), List(e), categories, Nil).map(_.category) mustBe Some(LinkedCategory.Multiple)
    }

    "label the category Unknown when it is not loaded or there are no splits" in {
      val missing = expense(List(split(UUID.randomUUID())))
      val noSplits = expense(Nil)
      matchedExpense(autoLink(missing), List(missing), categories, Nil).map(_.category) mustBe
        Some(LinkedCategory.Unknown)
      matchedExpense(autoLink(noSplits), List(noSplits), categories, Nil).map(_.category) mustBe
        Some(LinkedCategory.Unknown)
    }

    "fall back to the merchant list for the merchant name" in {
      val e = expense(List(split(groceries)), merchantId = Some(merchantId))
      matchedExpense(autoLink(e), List(e), categories, merchants).flatMap(_.merchantName) mustBe Some("Corner Shop")
    }

    "be None without a unique match to a loaded expense" in {
      val e = expense(List(split(groceries)))
      matchedExpense(Some(ReconciliationMatcher.NeedsReview(List(e.id))), List(e), categories, Nil) mustBe None
      matchedExpense(Some(ReconciliationMatcher.CreateNew), List(e), categories, Nil) mustBe None
      matchedExpense(None, List(e), categories, Nil) mustBe None
      matchedExpense(autoLink(e), Nil, categories, Nil) mustBe None
    }

    "key matchedExpenses by row id, keeping only AutoLink matches to loaded expenses" in {
      val e = expense(List(split(groceries)), merchantName = Some("Corner Shop"))
      val linkedRow, reviewRow, newRow, unloadedRow = UUID.randomUUID()
      val proposed = Map[UUID, ReconciliationMatcher.ProposedAction](
        linkedRow -> ReconciliationMatcher.AutoLink(e.id),
        reviewRow -> ReconciliationMatcher.NeedsReview(List(e.id)),
        newRow -> ReconciliationMatcher.CreateNew,
        unloadedRow -> ReconciliationMatcher.AutoLink(UUID.randomUUID())
      )
      BankStatementReviewLogic.matchedExpenses(proposed, List(e), categories, Nil) mustBe
        Map(linkedRow -> MatchedExpense(e.id, Some("Corner Shop"), LinkedCategory.Named("Groceries")))
    }
  }

  "linkPickerEntries" should {
    import BankStatementReviewLogic.linkPickerEntries

    val rowAmount = BigDecimal("20.00")
    val rowDate = LocalDate.of(2024, 3, 7)

    def expense(
        amount: String,
        notes: Option[String] = None,
        date: LocalDate = LocalDate.of(2024, 3, 7),
        rowIds: List[UUID] = Nil
    ) = GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal(amount),
      date = date,
      time = None,
      merchantId = None,
      merchantName = None,
      notes = notes,
      splits = Nil,
      rowIds = rowIds,
      createdAt = Instant.EPOCH
    )
    val others = (1 to 30).toList.map(i => expense(s"$i.00"))
    val matched = expense("99.99", notes = Some("Special"))
    val all = others :+ matched

    "pin the matched expense and cap the rest at 20" in {
      val (pinned, rest) = linkPickerEntries(all, "", Some(matched.id), rowAmount, rowDate)
      pinned mustBe Some(matched)
      rest.size mustBe 20
      rest must not contain matched
    }

    "pin the matched expense even when the filter excludes it" in {
      val (pinned, rest) = linkPickerEntries(all, "12.00", Some(matched.id), rowAmount, rowDate)
      pinned mustBe Some(matched)
      rest.map(_.amount) mustBe List(BigDecimal("12.00"))
    }

    "not list the matched expense twice" in {
      val (_, rest) = linkPickerEntries(all, "special", Some(matched.id), rowAmount, rowDate)
      rest mustBe Nil
    }

    "filter case-insensitively without a match" in {
      val (pinned, rest) = linkPickerEntries(all, "SPECIAL", None, rowAmount, rowDate)
      pinned mustBe None
      rest mustBe List(matched)
    }

    "rank the manual list by amount closeness to the row" in {
      val near = expense("20.50")
      val exact = expense("20.00")
      val far = expense("5.00")
      val (_, rest) = linkPickerEntries(List(near, far, exact), "", None, rowAmount, rowDate)
      rest.map(_.amount) mustBe List(BigDecimal("20.00"), BigDecimal("20.50"), BigDecimal("5.00"))
    }

    "break amount ties by date closeness to the row" in {
      val sameDay = expense("20.00", date = LocalDate.of(2024, 3, 7))
      val weekLater = expense("20.00", date = LocalDate.of(2024, 3, 14))
      val (_, rest) = linkPickerEntries(List(weekLater, sameDay), "", None, rowAmount, rowDate)
      rest.map(_.date) mustBe List(LocalDate.of(2024, 3, 7), LocalDate.of(2024, 3, 14))
    }

    "exclude expenses already linked to a row" in {
      val free = expense("20.00")
      val linked = expense("20.00", rowIds = List(UUID.randomUUID()))
      val (_, rest) = linkPickerEntries(List(linked, free), "", None, rowAmount, rowDate)
      rest mustBe List(free)
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

    "not mark an already linked row ready" in {
      val rules = List(rule(ImportRuleActionKind.Ignore, "SUPERMARKET", None))
      rowInfo(rules, Nil, Nil, None, sampleRow().copy(expenseId = Some(UUID.randomUUID()))).isReady mustBe false
    }

    "mark a positive row with an Ignore rule as ready, ignored by rule in one click" in {
      val row = sampleRow().copy(amount = BigDecimal("10.00"))
      val info = rowInfo(List(rule(ImportRuleActionKind.Ignore, "SUPERMARKET", None)), Nil, Nil, None, row)
      info.isReady mustBe true
      BankStatementReviewLogic.primaryActionFor(row, info.isReady, info.verdict, None) mustBe
        BankStatementReviewLogic.PrimaryAction.IgnoreByRule
    }

    "keep the rule merchant as prefill for a SetMerchant debit row whose merchant has no default category" in {
      val m = UUID.randomUUID()
      val info = rowInfo(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, "Walmart", None)),
        Nil,
        None,
        sampleRow()
      )
      info.isReady mustBe false
      info.resolved mustBe None
      info.prefillMerchantId mustBe Some(m)
      info.merchantName mustBe Some("Walmart")
    }

    "resolve a SetMerchant credit to a ready refund with the merchant's default category" in {
      val m = UUID.randomUUID()
      val c = UUID.randomUUID()
      val row = sampleRow().copy(amount = BigDecimal("10.00"))
      val info = rowInfo(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, "Walmart", Some(c))),
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        row
      )
      info.refund mustBe Some(BankStatementReviewLogic.RefundPrefill(Some(m), Some(c)))
      info.resolved mustBe None
      info.isReady mustBe true
      info.merchantName mustBe Some("Walmart")
      info.categoryName mustBe Some("Groceries")
      BankStatementReviewLogic.primaryActionFor(row, info.isReady, info.verdict, None) mustBe
        BankStatementReviewLogic.PrimaryAction.ReviewRefund
    }

    "resolve a SetMerchant credit whose merchant has no default category to a ready refund without a category" in {
      val m = UUID.randomUUID()
      val info = rowInfo(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(m))),
        List(merchant(m, "Walmart", None)),
        Nil,
        None,
        sampleRow().copy(amount = BigDecimal("10.00"))
      )
      info.refund mustBe Some(BankStatementReviewLogic.RefundPrefill(Some(m), None))
      info.isReady mustBe true
      info.categoryName mustBe None
    }

    "not mark a SetMerchant credit ready when the rule's merchant is not loaded" in {
      val info = rowInfo(
        List(rule(ImportRuleActionKind.SetMerchant, "SUPERMARKET", Some(UUID.randomUUID()))),
        Nil,
        Nil,
        None,
        sampleRow().copy(amount = BigDecimal("10.00"))
      )
      info.refund mustBe None
      info.isReady mustBe false
    }

    "resolve a SetCategory credit to a ready refund with that category and no merchant" in {
      val c = UUID.randomUUID()
      val info = rowInfo(
        List(ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory(c), "SUPERMARKET")),
        Nil,
        List(GetCategories.Response.Category(c, "Groceries", Instant.EPOCH)),
        None,
        sampleRow().copy(amount = BigDecimal("10.00"))
      )
      info.refund mustBe Some(BankStatementReviewLogic.RefundPrefill(None, Some(c)))
      info.isReady mustBe true
      info.categoryName mustBe Some("Groceries")
    }

    "not mark a credit with no rule ready, even with an AI suggestion" in {
      val row = sampleRow("UNKNOWN SHOP").copy(amount = BigDecimal("10.00"))
      val ai = Some(
        AiSuggestions(
          AiSuggestionsStatus.Done,
          "m",
          List(AiSuggestions.RowSuggestion(row.id, Some(UUID.randomUUID()), Some(UUID.randomUUID())))
        )
      )
      val info = rowInfo(Nil, Nil, Nil, ai, row)
      info.refund mustBe None
      info.resolved mustBe None
      info.isReady mustBe false
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

    "offer link, create expense, rule and ignore for an unprocessed debit row" in {
      menuItemsFor(sampleRow("UNKNOWN SHOP")) mustBe
        List(RowMenuItem.LinkExpense, RowMenuItem.CreateExpense, RowMenuItem.CreateRule, RowMenuItem.Ignore)
    }

    "offer link, rule and ignore for an unprocessed credit row" in {
      menuItemsFor(sampleRow("UNKNOWN SHOP").copy(amount = BigDecimal("45.00"))) mustBe
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
          refund = None,
          prefillMerchantId = None,
          isReady = true,
          merchantName = None,
          categoryName = None,
          linkedMerchantName = None,
          linkedCategory = LinkedCategory.Unknown
        )
      )
    val resolved = ResolvedImport(merchantId = None, categoryId = categoryId, categoryFromAi = false)

    "ignore a row with an Ignore verdict" in {
      BankStatementReviewLogic.bulkRowDecision(info(RowRuleVerdict.Ignore, None), None, None, Set.empty) mustBe
        BulkRowDecision.Ignore
    }

    "ignore a row with an Ignore verdict even when it has an expense match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.Ignore, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        None,
        Set.empty
      ) mustBe BulkRowDecision.Ignore
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.Ignore, Some(resolved)),
        Some(ReconciliationMatcher.NeedsReview(List(UUID.randomUUID(), UUID.randomUUID()))),
        None,
        Set.empty
      ) mustBe BulkRowDecision.Ignore
    }

    "link a row with a unique match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        None,
        Set.empty
      ) mustBe BulkRowDecision.Link(expenseId)
    }

    "fail a row whose unique match was already linked in this run" in {
      val decision = BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        None,
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
        None,
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
        None,
        Set.empty
      ) mustBe BulkRowDecision.Create(resolved, None)
    }

    "ignore an ignore-matched credit row" in {
      val credit = sampleRow().copy(amount = BigDecimal("10.00"))
      val creditInfo = BankStatementReviewLogic.rowInfo(
        List(ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.Ignore, "SUPERMARKET")),
        Nil,
        Nil,
        None,
        credit
      )
      BankStatementReviewLogic.bulkRowDecision(Some(creditInfo), None, None, Set.empty) mustBe BulkRowDecision.Ignore
    }

    "create a refund for a rule-matched credit row" in {
      val m = UUID.randomUUID()
      val credit = sampleRow().copy(amount = BigDecimal("10.00"))
      val creditInfo = BankStatementReviewLogic.rowInfo(
        List(ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetMerchant(m), "SUPERMARKET")),
        List(GetMerchants.Response.Merchant(m, "Walmart", None, None, None, reviewed = true, Instant.EPOCH)),
        Nil,
        None,
        credit
      )
      BankStatementReviewLogic.bulkRowDecision(Some(creditInfo), None, None, Set.empty) mustBe
        BulkRowDecision.CreateRefund(BankStatementReviewLogic.RefundPrefill(Some(m), None))
    }

    "fail with No category when the row resolves to nothing" in {
      BankStatementReviewLogic.bulkRowDecision(info(RowRuleVerdict.NoRule, None), None, None, Set.empty) mustBe
        BulkRowDecision.Fail("No category")
    }

    val receiptId = UUID.randomUUID()

    "link a row with a unique match, ignoring its receipt match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.AutoLink(expenseId)),
        Some(receiptId),
        Set.empty
      ) mustBe BulkRowDecision.Link(expenseId)
    }

    "create an expense with its receipt match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.CreateNew),
        Some(receiptId),
        Set.empty
      ) mustBe BulkRowDecision.Create(resolved, Some(receiptId))
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        None,
        Some(receiptId),
        Set.empty
      ) mustBe BulkRowDecision.Create(resolved, Some(receiptId))
    }

    "still fail a row with several expense matches when it has a receipt match" in {
      BankStatementReviewLogic.bulkRowDecision(
        info(RowRuleVerdict.NoRule, Some(resolved)),
        Some(ReconciliationMatcher.NeedsReview(List(UUID.randomUUID(), UUID.randomUUID()))),
        Some(receiptId),
        Set.empty
      ) mustBe a[BulkRowDecision.Fail]
    }
  }

  "receiptMatches" should {
    import BankStatementReviewLogic.{LinkedCategory, RefundPrefill, ResolvedImport, RowInfo}

    val resolved = ResolvedImport(merchantId = None, categoryId = UUID.randomUUID(), categoryFromAi = false)
    def info(
        verdict: RowRuleVerdict.Verdict = RowRuleVerdict.NoRule,
        isReady: Boolean = true,
        refund: Option[RefundPrefill] = None
    ): RowInfo =
      RowInfo(
        verdict = verdict,
        resolved = Some(resolved),
        refund = refund,
        prefillMerchantId = None,
        isReady = isReady,
        merchantName = None,
        categoryName = None,
        linkedMerchantName = None,
        linkedCategory = LinkedCategory.Unknown
      )
    def receipt(amount: String = "120.00", date: LocalDate = LocalDate.of(2024, 3, 6)) =
      GetReceipts.Response.Receipt(
        id = UUID.randomUUID(),
        documentFilename = "receipt.jpg",
        documentStatus = DocumentStatus.Parsed,
        amount = Some(BigDecimal(amount)),
        date = Some(date),
        merchantName = None,
        expenseId = None,
        ignoredAt = None,
        createdAt = Instant.EPOCH
      )

    "include a ready row with a single receipt match" in {
      val row = sampleRow()
      val r = receipt()
      BankStatementReviewLogic.receiptMatches(List(row), Map(row.id -> info()), Map.empty, List(r)) mustBe
        Map(row.id -> r)
      BankStatementReviewLogic.receiptMatches(
        List(row),
        Map(row.id -> info()),
        Map(row.id -> ReconciliationMatcher.CreateNew),
        List(r)
      ) mustBe Map(row.id -> r)
    }

    "exclude rows that would link to or need review against an existing expense" in {
      // Each row has its own receipt, so a wrongly included row shows up instead of cancelling out as a shared claim.
      val autoLink = sampleRow().copy(amount = BigDecimal("-11.00"))
      val review = sampleRow().copy(amount = BigDecimal("-12.00"))
      BankStatementReviewLogic.receiptMatches(
        List(autoLink, review),
        Map(autoLink.id -> info(), review.id -> info()),
        Map(
          autoLink.id -> ReconciliationMatcher.AutoLink(UUID.randomUUID()),
          review.id -> ReconciliationMatcher.NeedsReview(List(UUID.randomUUID(), UUID.randomUUID()))
        ),
        List(receipt(amount = "11.00"), receipt(amount = "12.00"))
      ) mustBe Map.empty
    }

    "exclude an Ignore-verdict row" in {
      val row = sampleRow()
      BankStatementReviewLogic.receiptMatches(
        List(row),
        Map(row.id -> info(verdict = RowRuleVerdict.Ignore)),
        Map.empty,
        List(receipt())
      ) mustBe Map.empty
    }

    "exclude a credit row" in {
      val row = sampleRow().copy(amount = BigDecimal("120.00"))
      BankStatementReviewLogic.receiptMatches(List(row), Map(row.id -> info()), Map.empty, List(receipt())) mustBe
        Map.empty
    }

    "exclude a not-ready row" in {
      val row = sampleRow()
      BankStatementReviewLogic.receiptMatches(
        List(row),
        Map(row.id -> info(isReady = false)),
        Map.empty,
        List(receipt())
      ) mustBe Map.empty
    }

    "exclude a row with a refund prefill" in {
      val row = sampleRow()
      BankStatementReviewLogic.receiptMatches(
        List(row),
        Map(row.id -> info(refund = Some(RefundPrefill(None, None)))),
        Map.empty,
        List(receipt())
      ) mustBe Map.empty
    }

    "exclude a row with two candidate receipts" in {
      val row = sampleRow()
      BankStatementReviewLogic.receiptMatches(
        List(row),
        Map(row.id -> info()),
        Map.empty,
        List(receipt(), receipt(date = LocalDate.of(2024, 3, 7)))
      ) mustBe Map.empty
    }

    "attach a receipt matching two ready rows to neither" in {
      val first = sampleRow()
      val second = sampleRow()
      val other = sampleRow().copy(amount = BigDecimal("-30.00"))
      val shared = receipt()
      val own = receipt(amount = "30.00")
      BankStatementReviewLogic.receiptMatches(
        List(first, second, other),
        Map(first.id -> info(), second.id -> info(), other.id -> info()),
        Map.empty,
        List(shared, own)
      ) mustBe Map(other.id -> own)
    }
  }

  "effectiveReceiptIds" should {
    def receipt() = GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt.jpg",
      documentStatus = DocumentStatus.Parsed,
      amount = Some(BigDecimal("10.00")),
      date = Some(LocalDate.of(2024, 3, 7)),
      merchantName = None,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.EPOCH
    )

    "drop a detached row and keep the others" in {
      val detachedRow = UUID.randomUUID()
      val keptRow = UUID.randomUUID()
      val detachedReceipt = receipt()
      val keptReceipt = receipt()
      BankStatementReviewLogic.effectiveReceiptIds(
        Map(detachedRow -> detachedReceipt, keptRow -> keptReceipt),
        Set(detachedRow)
      ) mustBe Map(keptRow -> keptReceipt.id)
    }
  }

  "receiptOffer" should {
    import BankStatementReviewLogic.{ReceiptOffer, receiptOffer}

    val receiptCategory = UUID.randomUUID()
    val receiptMerchant =
      GetMerchants.Response.Merchant(
        UUID.randomUUID(),
        "Costco",
        None,
        Some(receiptCategory),
        None,
        true,
        Instant.EPOCH
      )
    val currentMerchant =
      GetMerchants.Response.Merchant(UUID.randomUUID(), "Walmart", None, None, None, reviewed = true, Instant.EPOCH)
    val merchants = List(receiptMerchant, currentMerchant)

    def receipt(merchantId: Option[UUID]) = GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt.jpg",
      documentStatus = DocumentStatus.Parsed,
      amount = Some(BigDecimal("10.00")),
      date = Some(LocalDate.of(2024, 3, 7)),
      merchantName = Some("COSTCO"),
      merchantId = merchantId,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.EPOCH
    )

    "offer the receipt's merchant and its default category when it differs from the current merchant" in {
      val r = receipt(Some(receiptMerchant.id))
      receiptOffer(Some(r), Some(currentMerchant.id), merchants, Set.empty) mustBe
        Some(ReceiptOffer(r.id, receiptMerchant, Some(receiptCategory)))
    }

    "offer the receipt's merchant when the current merchant is free text or blank" in {
      val r = receipt(Some(receiptMerchant.id))
      receiptOffer(Some(r), None, merchants, Set.empty).map(_.merchant) mustBe Some(receiptMerchant)
    }

    "offer nothing once the receipt was decided" in {
      val r = receipt(Some(receiptMerchant.id))
      receiptOffer(Some(r), Some(currentMerchant.id), merchants, Set(r.id)) mustBe None
    }

    "offer nothing when the receipt's merchant is already the current merchant" in {
      val r = receipt(Some(receiptMerchant.id))
      receiptOffer(Some(r), Some(receiptMerchant.id), merchants, Set.empty) mustBe None
    }

    "offer nothing when the receipt has no merchant or one that is not among the user's merchants" in {
      receiptOffer(Some(receipt(None)), Some(currentMerchant.id), merchants, Set.empty) mustBe None
      receiptOffer(Some(receipt(Some(UUID.randomUUID()))), Some(currentMerchant.id), merchants, Set.empty) mustBe None
    }

    "offer nothing when no receipt is attached" in {
      receiptOffer(None, Some(currentMerchant.id), merchants, Set.empty) mustBe None
    }
  }

  "expenseMatches" should {
    import BankStatementReviewLogic.{LinkedCategory, RowInfo}

    def info(verdict: RowRuleVerdict.Verdict, isReady: Boolean): RowInfo =
      RowInfo(
        verdict = verdict,
        resolved = None,
        refund = None,
        prefillMerchantId = None,
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
      refund = None,
      prefillMerchantId = None,
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

  "backToListPath" should {
    "select the statement's account and round-trip through parseListQuery" in {
      val stmt = GetBankStatement.Response(
        id = UUID.randomUUID(),
        documentId = UUID.randomUUID(),
        documentFilename = "statement.pdf",
        documentStatus = DocumentStatus.Parsed,
        bankName = "Banco Azteca",
        productType = "credit",
        currency = "USD",
        accountLast4 = "1234",
        periodStart = LocalDate.of(2024, 3, 1),
        periodEnd = LocalDate.of(2024, 3, 31),
        statementRows = Nil,
        createdAt = Instant.now(),
        extractionInfo = None,
        aiSuggestions = None
      )
      val path = BankStatementReviewLogic.backToListPath(stmt)
      path must startWith("/bank-statements?")
      BankStatementsLogic.parseListQuery(path.stripPrefix("/bank-statements")) mustBe
        BankStatementsLogic.ListQuery(account = "Banco Azteca~credit~1234", name = "")
    }
  }

  "DrawerState" should {
    import BankStatementReviewLogic.DrawerState
    val rowId = UUID.randomUUID()

    "have no open row when closed" in {
      DrawerState.Closed.openRowId mustBe None
    }

    "expose the row of every open form" in {
      DrawerState.CreateExpense(rowId).openRowId mustBe Some(rowId)
      DrawerState.CreateRefund(rowId).openRowId mustBe Some(rowId)
      DrawerState.LinkExpense(rowId).openRowId mustBe Some(rowId)
      DrawerState.CreateRule(rowId).openRowId mustBe Some(rowId)
    }

    "close when the closing form is the one showing" in {
      DrawerState.CreateExpense(rowId).closing(DrawerState.CreateExpense(rowId)) mustBe DrawerState.Closed
    }

    "leave a different form open" in {
      val otherRow = UUID.randomUUID()
      DrawerState.LinkExpense(rowId).closing(DrawerState.CreateExpense(rowId)) mustBe DrawerState.LinkExpense(rowId)
      DrawerState.CreateExpense(otherRow).closing(DrawerState.CreateExpense(rowId)) mustBe
        DrawerState.CreateExpense(otherRow)
    }

    "stay closed when already closed" in {
      DrawerState.Closed.closing(DrawerState.CreateRule(rowId)) mustBe DrawerState.Closed
    }
  }

  "BulkRun" should {
    import BankStatementReviewLogic.BulkRun
    val ok = UUID.randomUUID() -> Right(())
    val bad = UUID.randomUUID() -> Left("boom")

    "have no results when idle" in {
      BulkRun.Idle.results mustBe Nil
      BulkRun.Idle.isRunning mustBe false
    }

    "append outcomes only while running" in {
      BulkRun.Running(2, Nil).recorded(ok._1, ok._2).recorded(bad._1, bad._2) mustBe BulkRun.Running(2, List(ok, bad))
      BulkRun.Idle.recorded(ok._1, ok._2) mustBe BulkRun.Idle
      BulkRun.Done(List(ok)).recorded(bad._1, bad._2) mustBe BulkRun.Done(List(ok))
    }

    "derive done and failed from the recorded results" in {
      val run: BulkRun.Running = BulkRun.Running(3, List(ok, bad))
      run.isRunning mustBe true
      run.done mustBe 2
      run.failed mustBe 1
    }

    "keep the results when finished" in {
      BulkRun.Running(2, List(ok, bad)).finished mustBe BulkRun.Done(List(ok, bad))
      BulkRun.Done(List(ok, bad)).results mustBe List(ok, bad)
    }

    "not be running once done" in {
      BulkRun.Done(List(ok)).isRunning mustBe false
      BulkRun.Running(1, Nil).finished.isRunning mustBe false
    }

    "stay idle when finishing an idle run" in {
      BulkRun.Idle.finished mustBe BulkRun.Idle
    }
  }

  "patchRow" should {
    "change only the row with the given id" in {
      val a = sampleRow("A")
      val b = sampleRow("B")
      val now = Instant.now()
      BankStatementReviewLogic.patchRow(List(a, b), b.id)(_.copy(ignoredAt = Some(now))) mustBe
        List(a, b.copy(ignoredAt = Some(now)))
    }

    "leave the rows unchanged when no row has the id" in {
      val rows = List(sampleRow("A"), sampleRow("B"))
      BankStatementReviewLogic.patchRow(rows, UUID.randomUUID())(_.copy(description = "X")) mustBe rows
    }
  }

  "replaceRow" should {
    "swap in the fresh copy at the same position" in {
      val a = sampleRow("A")
      val b = sampleRow("B")
      val c = sampleRow("C")
      val fresh = b.copy(description = "B2", expenseId = Some(UUID.randomUUID()))
      BankStatementReviewLogic.replaceRow(List(a, b, c), fresh) mustBe List(a, fresh, c)
    }

    "not add a row whose id is not in the list" in {
      val rows = List(sampleRow("A"))
      BankStatementReviewLogic.replaceRow(rows, sampleRow("B")) mustBe rows
    }
  }

  "ReviewSupport" should {
    import BankStatementReviewLogic.ReviewSupport
    def merchant(name: String) =
      GetMerchants.Response.Merchant(UUID.randomUUID(), name, None, None, None, reviewed = true, Instant.EPOCH)
    def rule(pattern: String) =
      ImportRuleMatcher.Rule(UUID.randomUUID(), ImportRuleAction.SetCategory(UUID.randomUUID()), pattern)
    def expense() = GetExpenses.Response.Expense(
      id = UUID.randomUUID(),
      amount = BigDecimal("10.00"),
      date = LocalDate.of(2024, 3, 7),
      time = None,
      merchantId = None,
      merchantName = None,
      notes = None,
      splits = Nil,
      createdAt = Instant.EPOCH
    )
    def receipt() = GetReceipts.Response.Receipt(
      id = UUID.randomUUID(),
      documentFilename = "receipt.jpg",
      documentStatus = DocumentStatus.Parsed,
      amount = Some(BigDecimal("10.00")),
      date = Some(LocalDate.of(2024, 3, 7)),
      merchantName = None,
      expenseId = None,
      ignoredAt = None,
      createdAt = Instant.EPOCH
    )
    val category = GetCategories.Response.Category(UUID.randomUUID(), "Food", Instant.EPOCH)
    val keptReceipt = receipt()
    val droppedReceipt = receipt()
    val base = ReviewSupport(
      List(merchant("M1")),
      List(category),
      List(rule("R1")),
      List(expense()),
      List(keptReceipt, droppedReceipt)
    )

    "append a merchant, keeping the other lists" in {
      val m = merchant("M2")
      base.withMerchant(m) mustBe base.copy(merchants = base.merchants :+ m)
    }

    "replace the expenses, keeping the other lists" in {
      val fresh = List(expense(), expense())
      base.withExpenses(fresh) mustBe base.copy(expenses = fresh)
    }

    "drop only the receipt with the given id, keeping the other lists" in {
      base.withoutReceipt(droppedReceipt.id) mustBe base.copy(receipts = List(keptReceipt))
    }

    "be unchanged when no receipt has the given id" in {
      base.withoutReceipt(UUID.randomUUID()) mustBe base
    }
  }

  "Loadable" should {
    import BankStatementReviewLogic.Loadable

    "expose the value only when loaded" in {
      Loadable.Loaded(1).toOption mustBe Some(1)
      Loadable.Loading.toOption mustBe None
      Loadable.Failed("boom").toOption mustBe None
    }

    "map only a loaded value, keeping loading and failures as they are" in {
      Loadable.Loaded(1).map(_ + 1) mustBe Loadable.Loaded(2)
      (Loadable.Loading: Loadable[Int]).map(_ + 1) mustBe Loadable.Loading
      (Loadable.Failed("boom"): Loadable[Int]).map(_ + 1) mustBe Loadable.Failed("boom")
    }
  }

  "bulkActionsEnabled" should {
    import BankStatementReviewLogic.{BulkRun, bulkActionsEnabled}

    "allow bulk actions once support is loaded and rows are selected" in {
      bulkActionsEnabled(supportLoaded = true, selectedCount = 2, BulkRun.Idle, BulkRun.Idle) mustBe true
    }

    "block bulk actions while the support lists are not loaded" in {
      bulkActionsEnabled(supportLoaded = false, selectedCount = 2, BulkRun.Idle, BulkRun.Idle) mustBe false
    }

    "block bulk actions when nothing is selected" in {
      bulkActionsEnabled(supportLoaded = true, selectedCount = 0, BulkRun.Idle, BulkRun.Idle) mustBe false
    }

    "block bulk actions while either run is in progress" in {
      bulkActionsEnabled(supportLoaded = true, 2, BulkRun.Running(1, Nil), BulkRun.Idle) mustBe false
      bulkActionsEnabled(supportLoaded = true, 2, BulkRun.Idle, BulkRun.Running(1, Nil)) mustBe false
    }
  }
}
